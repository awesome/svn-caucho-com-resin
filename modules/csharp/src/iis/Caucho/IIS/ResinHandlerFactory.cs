﻿/*
 * Copyright (c) 1998-2016 Caucho Technology -- all rights reserved
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
 * @author Alex Rojkov
 */

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Web;
using System.Diagnostics;
using System.Threading;

namespace Caucho.IIS
{
  class ResinHandlerFactory : IHttpHandlerFactory
  {
    private ResinHandler _resinHandler;

    public ResinHandlerFactory()
    {
      _resinHandler = new ResinHandler();
    }

    public IHttpHandler GetHandler(HttpContext context, string requestType, string url, string pathTranslated)
    {
      return _resinHandler;
    }

    public void ReleaseHandler(IHttpHandler handler)
    {
      //
    }

    public bool IsReusable { get { return true; } }
  }
}
