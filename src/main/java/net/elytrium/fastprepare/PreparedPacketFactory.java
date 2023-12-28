/*
 * Copyright (C) 2021 - 2023 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.fastprepare;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.natives.NativeSetupException;
import com.velocitypowered.natives.compression.VelocityCompressor;
import com.velocitypowered.natives.util.BufferPreference;
import com.velocitypowered.natives.util.Natives;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftCompressorAndLengthEncoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintLengthEncoder;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCounted;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.elytrium.commons.utils.reflection.ReflectionException;
import net.elytrium.fastprepare.dummy.DummyChannelHandlerContext;
import net.elytrium.fastprepare.handler.CompressionEventHandler;
import net.elytrium.fastprepare.handler.PreparedPacketEncoder;

@SuppressFBWarnings("RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE")
public class PreparedPacketFactory {

  public static final String PREPARED_ENCODER = "fastprepare-encoder";
  public static final String COMPRESSION_HANDLER = "fastprepare-compression-handler";
  private static final MethodHandle HANDLE_COMPRESSED;
  private static final MethodHandle ALLOCATE_COMPRESSED;
  private static final MethodHandle HANDLE_VARINT;
  private static final MethodHandle ALLOCATE_VARINT;
  private static final MethodHandle CLIENTBOUND_FIELD;
  private static final MethodHandle GET_PROTOCOL_REGISTRY;
  private static final MethodHandle PACKET_CLASS_TO_ID;
  private static final boolean DIRECT_BYTEBUF_PREFERRED_FOR_COMPRESSOR;

  private final Set<StateRegistry> stateRegistries = new HashSet<>();
  private final PreparedPacketConstructor constructor;
  private final Map<Thread, MinecraftCompressorAndLengthEncoder> compressionEncoder;
  private final boolean releaseReferenceCounted;
  private final ByteBufAllocator preparedPacketAllocator;
  private final ChannelHandlerContext dummyContext;
  private boolean enableCompression;
  private int compressionThreshold;
  private int compressionLevel;
  private boolean saveUncompressed;

  static {
    try {
      HANDLE_COMPRESSED = MethodHandles.privateLookupIn(MinecraftCompressorAndLengthEncoder.class, MethodHandles.lookup())
          .findVirtual(MinecraftCompressorAndLengthEncoder.class, "encode",
              MethodType.methodType(void.class, ChannelHandlerContext.class, ByteBuf.class, ByteBuf.class));
      ALLOCATE_COMPRESSED = MethodHandles.privateLookupIn(MinecraftCompressorAndLengthEncoder.class, MethodHandles.lookup())
          .findVirtual(MinecraftCompressorAndLengthEncoder.class, "allocateBuffer",
              MethodType.methodType(ByteBuf.class, ChannelHandlerContext.class, ByteBuf.class, boolean.class));
      HANDLE_VARINT = MethodHandles.privateLookupIn(MinecraftVarintLengthEncoder.class, MethodHandles.lookup())
          .findVirtual(MinecraftVarintLengthEncoder.class, "encode",
              MethodType.methodType(void.class, ChannelHandlerContext.class, ByteBuf.class, ByteBuf.class));
      ALLOCATE_VARINT = MethodHandles.privateLookupIn(MinecraftVarintLengthEncoder.class, MethodHandles.lookup())
          .findVirtual(MinecraftVarintLengthEncoder.class, "allocateBuffer",
              MethodType.methodType(ByteBuf.class, ChannelHandlerContext.class, ByteBuf.class, boolean.class));
      CLIENTBOUND_FIELD = MethodHandles.privateLookupIn(StateRegistry.class, MethodHandles.lookup())
          .findGetter(StateRegistry.class, "clientbound", StateRegistry.PacketRegistry.class);
      GET_PROTOCOL_REGISTRY = MethodHandles.privateLookupIn(StateRegistry.PacketRegistry.class, MethodHandles.lookup())
          .findVirtual(StateRegistry.PacketRegistry.class, "getProtocolRegistry",
              MethodType.methodType(StateRegistry.PacketRegistry.ProtocolRegistry.class, ProtocolVersion.class));
      PACKET_CLASS_TO_ID = MethodHandles.privateLookupIn(StateRegistry.PacketRegistry.ProtocolRegistry.class, MethodHandles.lookup())
          .findGetter(StateRegistry.PacketRegistry.ProtocolRegistry.class, "packetClassToId", Object2IntMap.class);

      try (VelocityCompressor compressor = Natives.compress.get().create(1)) {
        BufferPreference bufferType = compressor.preferredBufferType();
        DIRECT_BYTEBUF_PREFERRED_FOR_COMPRESSOR = bufferType.equals(BufferPreference.DIRECT_PREFERRED)
            || bufferType.equals(BufferPreference.DIRECT_REQUIRED);
      }
    } catch (NoSuchMethodException | IllegalAccessException | NoSuchFieldException e) {
      throw new ReflectionException(e);
    }
  }

  public PreparedPacketFactory(PreparedPacketConstructor constructor, StateRegistry stateRegistry, boolean enableCompression,
                               int compressionLevel, int compressionThreshold, boolean saveUncompressed, boolean releaseReferenceCounted) {
    this(constructor, stateRegistry, enableCompression, compressionLevel, compressionThreshold, saveUncompressed, releaseReferenceCounted,
        new PooledByteBufAllocator());
  }

  public PreparedPacketFactory(PreparedPacketConstructor constructor, StateRegistry stateRegistry, boolean enableCompression,
                               int compressionLevel, int compressionThreshold, boolean saveUncompressed, boolean releaseReferenceCounted,
                               ByteBufAllocator preparedPacketAllocator) {
    this(constructor, Collections.singleton(stateRegistry), enableCompression, compressionLevel, compressionThreshold, saveUncompressed,
        releaseReferenceCounted, preparedPacketAllocator);
  }

  public PreparedPacketFactory(PreparedPacketConstructor constructor, Collection<StateRegistry> stateRegistries, boolean enableCompression,
                               int compressionLevel, int compressionThreshold, boolean saveUncompressed, boolean releaseReferenceCounted,
                               ByteBufAllocator preparedPacketAllocator) {
    this.constructor = constructor;
    this.stateRegistries.addAll(stateRegistries);
    this.compressionEncoder = Collections.synchronizedMap(new HashMap<>());
    this.updateCompressor(enableCompression, compressionLevel, compressionThreshold, saveUncompressed);
    this.releaseReferenceCounted = releaseReferenceCounted;
    this.preparedPacketAllocator = preparedPacketAllocator;
    this.dummyContext = new DummyChannelHandlerContext(preparedPacketAllocator);
  }

  public void updateCompressor(boolean enableCompression, int compressionLevel, int compressionThreshold, boolean saveUncompressed) {
    this.enableCompression = enableCompression;
    this.compressionLevel = compressionLevel;
    this.compressionThreshold = compressionThreshold;
    this.saveUncompressed = saveUncompressed && enableCompression;
  }

  public void releaseThread(Thread thread) {
    if (this.compressionEncoder.containsKey(thread)) {
      try {
        this.compressionEncoder.remove(thread).handlerRemoved(this.dummyContext);
      } catch (Exception e) {
        throw new NativeSetupException(e);
      }
    }
  }

  private MinecraftCompressorAndLengthEncoder getThreadLocalCompressionEncoder() {
    // We're creating different compressors for different threads here to allow multithreading
    return this.compressionEncoder.computeIfAbsent(Thread.currentThread(), (key) ->
        new MinecraftCompressorAndLengthEncoder(this.compressionThreshold, Natives.compress.get().create(this.compressionLevel)));
  }

  public PreparedPacket createPreparedPacket(ProtocolVersion minVersion, ProtocolVersion maxVersion) {
    return this.constructor.construct(minVersion, maxVersion, this);
  }

  @SuppressWarnings("unchecked")
  public void encodeId(MinecraftPacket packet, ByteBuf out, ProtocolVersion version) {
    try {
      int packetId = Integer.MIN_VALUE;
      for (StateRegistry stateRegistry : this.stateRegistries) {
        StateRegistry.PacketRegistry packetRegistry = (StateRegistry.PacketRegistry) CLIENTBOUND_FIELD.invokeExact(stateRegistry);
        StateRegistry.PacketRegistry.ProtocolRegistry protocolRegistry
            = (StateRegistry.PacketRegistry.ProtocolRegistry) GET_PROTOCOL_REGISTRY.invokeExact(packetRegistry, version);
        Object2IntMap<Class<? extends MinecraftPacket>> classToId
            = (Object2IntMap<Class<? extends MinecraftPacket>>) PACKET_CLASS_TO_ID.invokeExact(protocolRegistry);
        packetId = classToId.getInt(packet.getClass());
        if (packetId != Integer.MIN_VALUE) {
          break;
        }
      }

      if (packetId == Integer.MIN_VALUE) {
        throw new IllegalArgumentException(String.format(
            "Unable to find id for packet of type %s in clientbound protocol %s.", packet.getClass().getName(), version));
      }

      ProtocolUtils.writeVarInt(out, packetId);
      packet.encode(out, ProtocolUtils.Direction.CLIENTBOUND, version);
    } catch (Throwable e) {
      throw new ReflectionException(e);
    }
  }

  public ByteBuf compress(ByteBuf packetData, boolean enableCompression) {
    ByteBuf networkPacket;

    try {
      if (enableCompression) {
        networkPacket = (ByteBuf) ALLOCATE_COMPRESSED.invokeExact(this.getThreadLocalCompressionEncoder(), this.dummyContext, packetData, false);
        HANDLE_COMPRESSED.invokeExact(this.getThreadLocalCompressionEncoder(), this.dummyContext, packetData, networkPacket);
      } else {
        networkPacket = (ByteBuf) ALLOCATE_VARINT.invokeExact(MinecraftVarintLengthEncoder.INSTANCE, this.dummyContext, packetData, false);
        HANDLE_VARINT.invokeExact(MinecraftVarintLengthEncoder.INSTANCE, this.dummyContext, packetData, networkPacket);
      }
    } catch (Throwable e) {
      throw new ReflectionException(e);
    }

    packetData.release();
    return networkPacket;
  }

  public ByteBuf encodeSingle(MinecraftPacket packet, ProtocolVersion version) {
    return this.encodeSingle(packet, version, this.enableCompression);
  }

  public ByteBuf encodeSingle(MinecraftPacket packet, ProtocolVersion version, ByteBufAllocator alloc) {
    return this.encodeSingle(packet, version, this.enableCompression, this.releaseReferenceCounted, alloc);
  }

  public ByteBuf encodeSingle(MinecraftPacket packet, ProtocolVersion version, boolean enableCompression) {
    return this.encodeSingle(packet, version, enableCompression, this.releaseReferenceCounted, this.preparedPacketAllocator);
  }

  public ByteBuf encodeSingle(MinecraftPacket packet, ProtocolVersion version, boolean enableCompression, boolean releaseReferenceCounted) {
    return this.encodeSingle(packet, version, enableCompression, releaseReferenceCounted, this.preparedPacketAllocator);
  }

  public ByteBuf encodeSingle(MinecraftPacket packet, ProtocolVersion version, boolean enableCompression, ByteBufAllocator alloc) {
    return this.encodeSingle(packet, version, enableCompression, this.releaseReferenceCounted, alloc);
  }

  public ByteBuf encodeSingle(MinecraftPacket packet, ProtocolVersion version, boolean enableCompression, boolean releaseReferenceCounted,
                              ByteBufAllocator alloc) {
    ByteBuf packetData;

    if (enableCompression) {
      packetData = DIRECT_BYTEBUF_PREFERRED_FOR_COMPRESSOR ? alloc.directBuffer() : alloc.buffer();
    } else {
      // Ignoring Cipher there.
      // Network I/O always works better with direct buffers
      packetData = alloc.directBuffer();
    }

    this.encodeId(packet, packetData, version);

    if (releaseReferenceCounted && packet instanceof ReferenceCounted referenceCounted) {
      referenceCounted.release();
    }

    return this.compress(packetData, version.compareTo(ProtocolVersion.MINECRAFT_1_8) >= 0 && enableCompression);
  }

  public void inject(Player player, MinecraftConnection connection, ChannelPipeline pipeline) {
    pipeline.addAfter(Connections.MINECRAFT_ENCODER, PREPARED_ENCODER,
        new PreparedPacketEncoder(this, connection.getProtocolVersion(), player.isOnlineMode()));
    pipeline.addFirst(COMPRESSION_HANDLER, new CompressionEventHandler(this));
  }

  public void replace(ChannelPipeline pipeline) {
    pipeline.get(PreparedPacketEncoder.class).setFactory(this);
  }

  public void setShouldSendUncompressed(ChannelPipeline pipeline, boolean shouldSendUncompressed) {
    pipeline.get(PreparedPacketEncoder.class).setShouldSendUncompressed(shouldSendUncompressed);
  }

  public void deject(ChannelPipeline pipeline) {
    if (pipeline.names().contains(PREPARED_ENCODER)) {
      pipeline.remove(PreparedPacketEncoder.class);
      pipeline.remove(CompressionEventHandler.class);
    }
  }

  public boolean isCompressionEnabled() {
    return this.enableCompression;
  }

  public boolean shouldSaveUncompressed() {
    return this.saveUncompressed;
  }

  public boolean shouldReleaseReferenceCounted() {
    return this.releaseReferenceCounted;
  }

  public ByteBufAllocator getPreparedPacketAllocator() {
    return this.preparedPacketAllocator;
  }

  public void addStateRegistries(Collection<StateRegistry> stateRegistries) {
    this.stateRegistries.addAll(stateRegistries);
  }

  public void addStateRegistry(StateRegistry stateRegistry) {
    this.stateRegistries.add(stateRegistry);
  }
}
