/*
 * Copyright (C) 2021 - 2022 Elytrium
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
import com.velocitypowered.natives.util.Natives;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftCompressorAndLengthEncoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintLengthEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.elytrium.fastprepare.dummy.DummyChannelHandlerContext;
import net.elytrium.fastprepare.encoder.PreparedPacketEncoder;
import net.elytrium.fastprepare.encoder.SinglePacketEncoder;

public class PreparedPacketFactory {

  public static final String PREPARED_ENCODER = "prepared-encoder";
  public static final String SINGLE_ENCODER = "single-encoder";
  private static final ChannelHandlerContext dummyContext = new DummyChannelHandlerContext();
  private static Method handleCompressed;
  private static Method allocateCompressed;
  private static Method handleVarint;
  private static Method allocateVarint;

  private final PreparedPacketConstructor constructor;
  private final StateRegistry stateRegistry;
  private boolean enableCompression;
  private ThreadLocal<MinecraftCompressorAndLengthEncoder> compressionEncoder;

  static {
    try {
      handleCompressed = MinecraftCompressorAndLengthEncoder.class
          .getDeclaredMethod("encode", ChannelHandlerContext.class, ByteBuf.class, ByteBuf.class);
      handleCompressed.setAccessible(true);
      allocateCompressed = MinecraftCompressorAndLengthEncoder.class
          .getDeclaredMethod("allocateBuffer", ChannelHandlerContext.class, ByteBuf.class, boolean.class);
      allocateCompressed.setAccessible(true);

      handleVarint = MinecraftVarintLengthEncoder.class
          .getDeclaredMethod("encode", ChannelHandlerContext.class, ByteBuf.class, ByteBuf.class);
      handleVarint.setAccessible(true);
      allocateVarint = MinecraftVarintLengthEncoder.class
          .getDeclaredMethod("allocateBuffer", ChannelHandlerContext.class, ByteBuf.class, boolean.class);
      allocateVarint.setAccessible(true);
    } catch (NoSuchMethodException e) {
      e.printStackTrace();
    }
  }

  public PreparedPacketFactory(PreparedPacketConstructor constructor, StateRegistry stateRegistry, boolean enableCompression,
                               int compressionLevel, int compressionThreshold) {
    this.constructor = constructor;
    this.stateRegistry = stateRegistry;
    this.updateCompressor(enableCompression, compressionLevel, compressionThreshold);
  }

  public void updateCompressor(boolean enableCompression, int compressionLevel, int compressionThreshold) {
    this.enableCompression = enableCompression;
    this.compressionEncoder = ThreadLocal.withInitial(() ->
        new MinecraftCompressorAndLengthEncoder(compressionThreshold, Natives.compress.get().create(compressionLevel)));
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
        networkPacket = (ByteBuf) allocateCompressed.invoke(this.compressionEncoder.get(), dummyContext, packetData, false);
        handleCompressed.invoke(this.compressionEncoder.get(), dummyContext, packetData, networkPacket);
      } else {
        networkPacket = (ByteBuf) allocateVarint.invoke(MinecraftVarintLengthEncoder.INSTANCE, dummyContext, packetData, false);
        handleVarint.invoke(MinecraftVarintLengthEncoder.INSTANCE, dummyContext, packetData, networkPacket);
      }
    } catch (IllegalAccessException | InvocationTargetException e) {
      e.printStackTrace();
      return null;
    }

    packetData.release();
    return networkPacket;
  }

  public ByteBuf encodeSingle(MinecraftPacket packet, ProtocolVersion version) {
    ByteBuf packetData = Unpooled.buffer();
    this.encodeId(packet, packetData, version);

    return this.compress(packetData, version.compareTo(ProtocolVersion.MINECRAFT_1_8) >= 0 && this.enableCompression);
  }

  public void inject(Player player, MinecraftConnection connection, ChannelPipeline pipeline) {
    pipeline.addAfter(Connections.MINECRAFT_ENCODER, PREPARED_ENCODER, new PreparedPacketEncoder(connection.getProtocolVersion(), player.isOnlineMode()));
    pipeline.addAfter(PREPARED_ENCODER, SINGLE_ENCODER, new SinglePacketEncoder(this, connection.getProtocolVersion()));
  }

  public void deject(ChannelPipeline pipeline) {
    if (pipeline.names().contains(PREPARED_ENCODER)) {
      pipeline.remove(PreparedPacketEncoder.class);
      pipeline.remove(SinglePacketEncoder.class);
    }
  }
}
