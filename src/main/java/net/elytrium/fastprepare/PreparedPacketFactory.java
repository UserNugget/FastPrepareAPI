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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.elytrium.fastprepare.dummy.DummyChannelHandlerContext;
import net.elytrium.fastprepare.handler.CompressionEventHandler;
import net.elytrium.fastprepare.handler.PreparedPacketEncoder;

@SuppressFBWarnings("RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE")
public class PreparedPacketFactory {

  public static final String PREPARED_ENCODER = "fastprepare-encoder";
  public static final String COMPRESSION_HANDLER = "fastprepare-compression-handler";
  private static final ChannelHandlerContext DUMMY_CONTEXT = new DummyChannelHandlerContext();
  private static Method HANDLE_COMPRESSED;
  private static Method ALLOCATE_COMPRESSED;
  private static Method HANDLE_VARINT;
  private static Method ALLOCATE_VARINT;
  private static boolean DIRECT_BYTEBUF_PREFERRED_FOR_COMPRESSOR;

  private final PreparedPacketConstructor constructor;
  private final StateRegistry stateRegistry;
  private final Map<Thread, MinecraftCompressorAndLengthEncoder> compressionEncoder;
  private boolean enableCompression;
  private int compressionThreshold;
  private int compressionLevel;
  private boolean saveUncompressed;

  static {
    try {
      HANDLE_COMPRESSED = MinecraftCompressorAndLengthEncoder.class
          .getDeclaredMethod("encode", ChannelHandlerContext.class, ByteBuf.class, ByteBuf.class);
      HANDLE_COMPRESSED.setAccessible(true);
      ALLOCATE_COMPRESSED = MinecraftCompressorAndLengthEncoder.class
          .getDeclaredMethod("allocateBuffer", ChannelHandlerContext.class, ByteBuf.class, boolean.class);
      ALLOCATE_COMPRESSED.setAccessible(true);

      HANDLE_VARINT = MinecraftVarintLengthEncoder.class
          .getDeclaredMethod("encode", ChannelHandlerContext.class, ByteBuf.class, ByteBuf.class);
      HANDLE_VARINT.setAccessible(true);
      ALLOCATE_VARINT = MinecraftVarintLengthEncoder.class
          .getDeclaredMethod("allocateBuffer", ChannelHandlerContext.class, ByteBuf.class, boolean.class);
      ALLOCATE_VARINT.setAccessible(true);

      try (VelocityCompressor compressor = Natives.compress.get().create(1)) {
        BufferPreference bufferType = compressor.preferredBufferType();
        DIRECT_BYTEBUF_PREFERRED_FOR_COMPRESSOR = bufferType.equals(BufferPreference.DIRECT_PREFERRED)
            || bufferType.equals(BufferPreference.DIRECT_REQUIRED);
      }
    } catch (NoSuchMethodException e) {
      e.printStackTrace();
    }
  }

  public PreparedPacketFactory(PreparedPacketConstructor constructor, StateRegistry stateRegistry, boolean enableCompression,
                               int compressionLevel, int compressionThreshold, boolean saveUncompressed) {
    this.constructor = constructor;
    this.stateRegistry = stateRegistry;
    this.compressionEncoder = Collections.synchronizedMap(new HashMap<>());
    this.updateCompressor(enableCompression, compressionLevel, compressionThreshold, saveUncompressed);
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
        this.compressionEncoder.remove(thread).handlerRemoved(DUMMY_CONTEXT);
      } catch (Exception e) {
        e.printStackTrace();
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

  public void encodeId(MinecraftPacket packet, ByteBuf out, ProtocolVersion version) {
    ProtocolUtils.writeVarInt(out, ProtocolUtils.Direction.CLIENTBOUND.getProtocolRegistry(this.stateRegistry, version).getPacketId(packet));
    packet.encode(out, ProtocolUtils.Direction.CLIENTBOUND, version);
  }

  public ByteBuf compress(ByteBuf packetData, boolean enableCompression) {
    ByteBuf networkPacket;

    try {
      if (enableCompression) {
        networkPacket = (ByteBuf) ALLOCATE_COMPRESSED.invoke(this.getThreadLocalCompressionEncoder(), DUMMY_CONTEXT, packetData, false);
        HANDLE_COMPRESSED.invoke(this.getThreadLocalCompressionEncoder(), DUMMY_CONTEXT, packetData, networkPacket);
      } else {
        networkPacket = (ByteBuf) ALLOCATE_VARINT.invoke(MinecraftVarintLengthEncoder.INSTANCE, DUMMY_CONTEXT, packetData, false);
        HANDLE_VARINT.invoke(MinecraftVarintLengthEncoder.INSTANCE, DUMMY_CONTEXT, packetData, networkPacket);
      }
    } catch (IllegalAccessException | InvocationTargetException e) {
      e.printStackTrace();
      return null;
    }

    packetData.release();
    return networkPacket;
  }

  public ByteBuf encodeSingle(MinecraftPacket packet, ProtocolVersion version) {
    return this.encodeSingle(packet, version, this.enableCompression);
  }

  public ByteBuf encodeSingle(MinecraftPacket packet, ProtocolVersion version, boolean enableCompression) {
    ByteBuf packetData;

    if (enableCompression) {
      packetData = DIRECT_BYTEBUF_PREFERRED_FOR_COMPRESSOR ? Unpooled.directBuffer() : Unpooled.buffer();
    } else {
      // Ignoring Cipher there.
      // Network I/O always works better with direct buffers
      packetData = Unpooled.directBuffer();
    }

    this.encodeId(packet, packetData, version);

    return this.compress(packetData, version.compareTo(ProtocolVersion.MINECRAFT_1_8) >= 0 && enableCompression);
  }

  public void inject(Player player, MinecraftConnection connection, ChannelPipeline pipeline) {
    pipeline.addAfter(Connections.MINECRAFT_ENCODER, PREPARED_ENCODER, new PreparedPacketEncoder(this, connection.getProtocolVersion(), player.isOnlineMode()));
    pipeline.addFirst(COMPRESSION_HANDLER, new CompressionEventHandler(this));
  }

  public void setShouldSendUncompressed(ChannelPipeline pipeline, boolean shouldSendUncompressed) {
    PreparedPacketEncoder encoder = (PreparedPacketEncoder) pipeline.get(PREPARED_ENCODER);
    encoder.setShouldSendUncompressed(shouldSendUncompressed);
  }

  public void deject(ChannelPipeline pipeline) {
    if (pipeline.names().contains(PREPARED_ENCODER)) {
      pipeline.remove(PreparedPacketEncoder.class);
      pipeline.remove(CompressionEventHandler.class);
    }
  }

  public boolean shouldSaveUncompressed() {
    return this.saveUncompressed;
  }
}
