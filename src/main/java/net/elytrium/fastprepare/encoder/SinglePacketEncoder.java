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
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import java.util.List;
import net.elytrium.fastprepare.PreparedPacketFactory;

public class SinglePacketEncoder extends MessageToMessageEncoder<MinecraftPacket> {

  private final PreparedPacketFactory factory;
  private final ProtocolVersion protocolVersion;

  public SinglePacketEncoder(PreparedPacketFactory factory, ProtocolVersion protocolVersion) {
    this.factory = factory;
    this.protocolVersion = protocolVersion;
  }

  @Override
  protected void encode(ChannelHandlerContext ctx, MinecraftPacket msg, List<Object> out) {
    out.add(this.factory.encodeSingle(msg, this.protocolVersion));
  }
}
