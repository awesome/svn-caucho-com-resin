/*
 * Copyright (c) 1998-2012 Caucho Technology -- all rights reserved
 *
 * This file is part of Resin(R) Open Source
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Resin Open Source is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Resin Open Source is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, or any warranty
 * of NON-INFRINGEMENT.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Resin Open Source; if not, write to the
 *
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package com.caucho.amp.router;

import java.util.logging.Logger;

import com.caucho.amp.actor.AmpActorRef;
import com.caucho.amp.mailbox.AmpMailbox;
import com.caucho.amp.stream.AmpEncoder;
import com.caucho.amp.stream.AmpError;
import com.caucho.amp.stream.AmpHeaders;

/**
 * AmpRouter routes messages to mailboxes.
 */
public class AbstractAmpBroker implements AmpBroker
{
  private static final Logger log
    = Logger.getLogger(AbstractAmpBroker.class.getName());

  @Override
  public AmpActorRef getActorRef(String to)
  {
    throw new UnsupportedOperationException(getClass().getName());
  }

  /* (non-Javadoc)
   * @see com.caucho.amp.router.AmpRouter#addMailbox(com.caucho.amp.mailbox.AmpMailbox)
   */
  @Override
  public void addMailbox(String address, AmpMailbox mailbox)
  {
    // TODO Auto-generated method stub
    
  }

  /* (non-Javadoc)
   * @see com.caucho.amp.router.AmpRouter#removeMailbox(com.caucho.amp.mailbox.AmpMailbox)
   */
  @Override
  public void removeMailbox(String address, AmpMailbox mailbox)
  {
    // TODO Auto-generated method stub
    
  }
  /* (non-Javadoc)
   * @see com.caucho.amp.router.AmpRouter#close()
   */
  @Override
  public void close()
  {
    // TODO Auto-generated method stub
    
  }

  /* (non-Javadoc)
   * @see com.caucho.amp.router.AmpBroker#getRouterMailbox()
   */
  @Override
  public AmpMailbox getRouterMailbox()
  {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public void send(String to, 
                   String from, 
                   AmpEncoder encoder,
                   String methodName, 
                   Object... args)
  {
    getActorRef(to).send(getActorRef(from), encoder, methodName, args);
  }

  @Override
  public void query(long id, 
                    String to, 
                    String from, 
                    AmpEncoder encoder,
                    String methodName, 
                    Object... args)
  {
    getActorRef(to).query(id, getActorRef(from), encoder, methodName, args);
  }

  @Override
  public void reply(long id, 
                    String to, 
                    String from, 
                    AmpEncoder encoder,
                    Object result)
  {
    getActorRef(to).reply(id, getActorRef(from), encoder, result);
  }
}