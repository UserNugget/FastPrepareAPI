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

package net.elytrium.fastprepare.handler;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.function.Function;
import net.elytrium.fastprepare.PreparedPacket;
import net.elytrium.fastprepare.PreparedPacketFactory;
import net.elytrium.fastprepare.dummy.DummyPacket;

public class PreparedPacketEncoder extends ChannelOutboundHandlerAdapter {

  private final ProtocolVersion protocolVersion;
  private final Function<ByteBuf, ByteBuf> duplicateFunction;
  private PreparedPacketFactory factory;
  private boolean shouldSendUncompressed = true;

  public PreparedPacketEncoder(PreparedPacketFactory factory, ProtocolVersion protocolVersion, boolean shouldCopy) {
    this.factory = factory;
    this.protocolVersion = protocolVersion;
    this.duplicateFunction = shouldCopy ? ByteBuf::copy : ByteBuf::retainedDuplicate;
  }

  public PreparedPacketEncoder(PreparedPacketFactory factory, ProtocolVersion protocolVersion, Function<ByteBuf, ByteBuf> duplicateFunction) {
    this.factory = factory;
    this.protocolVersion = protocolVersion;
    this.duplicateFunction = duplicateFunction;
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
    if (msg instanceof DummyPacket) {
      return;
    }

    if (msg instanceof PreparedPacket preparedPacket) {
      ByteBuf cachedPacket = this.isSendUncompressed()
          ? preparedPacket.getUncompressedPackets(this.protocolVersion) : preparedPacket.getPackets(this.protocolVersion);

      if (cachedPacket == null) {
        throw new IllegalStateException("Current PreparedPacket is not prepared for " + this.protocolVersion);
      }

      ctx.write(this.duplicateFunction.apply(cachedPacket), promise);
    } else if (msg instanceof MinecraftPacket) {
      if (this.isSendUncompressed()) {
        ctx.write(this.factory.encodeSingle((MinecraftPacket) msg, this.protocolVersion, false, ctx.alloc()), promise);
      } else {
        ctx.write(this.factory.encodeSingle((MinecraftPacket) msg, this.protocolVersion, ctx.alloc()), promise);
      }
    } else {
      ctx.write(msg, promise);
    }
  }

  public boolean isSendUncompressed() {
    return this.factory.shouldSaveUncompressed() && this.shouldSendUncompressed;
  }

  public PreparedPacketFactory getFactory() {
    return this.factory;
  }

  public void setFactory(PreparedPacketFactory factory) {
    this.factory = factory;
  }

  public void setShouldSendUncompressed(boolean shouldSendUncompressed) {
    this.shouldSendUncompressed = shouldSendUncompressed;
  }
}
