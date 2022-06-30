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

package net.elytrium.fastprepare.encoder;

import com.velocitypowered.api.network.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import java.util.List;
import java.util.function.Function;
import net.elytrium.fastprepare.PreparedPacket;

public class PreparedPacketEncoder extends MessageToMessageEncoder<PreparedPacket> {

  private final ProtocolVersion protocolVersion;
  private final Function<ByteBuf, ByteBuf> duplicateFunction;

  public PreparedPacketEncoder(ProtocolVersion protocolVersion, boolean shouldCopy) {
    this.protocolVersion = protocolVersion;
    this.duplicateFunction = shouldCopy ? ByteBuf::copy : ByteBuf::retainedDuplicate;
  }

  public PreparedPacketEncoder(ProtocolVersion protocolVersion, Function<ByteBuf, ByteBuf> duplicateFunction) {
    this.protocolVersion = protocolVersion;
    this.duplicateFunction = duplicateFunction;
  }

  @Override
  protected void encode(ChannelHandlerContext ctx, PreparedPacket msg, List<Object> out) {
    if (msg.hasPacketsFor(this.protocolVersion)) {
      out.add(this.duplicateFunction.apply(msg.getPackets(this.protocolVersion)));
    }
  }
}
