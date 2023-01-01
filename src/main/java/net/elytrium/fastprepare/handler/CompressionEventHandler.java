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

import com.velocitypowered.proxy.protocol.VelocityConnectionEvent;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.elytrium.fastprepare.PreparedPacketFactory;

public class CompressionEventHandler extends ChannelInboundHandlerAdapter {

  private final PreparedPacketFactory factory;

  public CompressionEventHandler(PreparedPacketFactory factory) {
    this.factory = factory;
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
    if (event instanceof VelocityConnectionEvent) {
      VelocityConnectionEvent velocityEvent = (VelocityConnectionEvent) event;
      if (velocityEvent == VelocityConnectionEvent.COMPRESSION_ENABLED) {
        this.factory.setShouldSendUncompressed(ctx.pipeline(), false);
      } else if (velocityEvent == VelocityConnectionEvent.COMPRESSION_DISABLED) {
        this.factory.setShouldSendUncompressed(ctx.pipeline(), true);
      }
    }

    ctx.fireUserEventTriggered(event);
  }
}
