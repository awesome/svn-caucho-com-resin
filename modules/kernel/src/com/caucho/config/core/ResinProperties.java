/*
 * Copyright (c) 1998-2011 Caucho Technology -- all rights reserved
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

package com.caucho.config.core;

import com.caucho.config.Config;
import com.caucho.config.ConfigException;
import com.caucho.config.SchemaBean;
import com.caucho.config.type.FlowBean;
import com.caucho.config.types.FileSetType;
import com.caucho.loader.Environment;
import com.caucho.util.IoUtil;
import com.caucho.util.L10N;
import com.caucho.vfs.Depend;
import com.caucho.vfs.Path;
import com.caucho.vfs.ReadStream;

import javax.annotation.PostConstruct;

import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Imports properties values from a separate file.
 */
public class ResinProperties extends ResinControl implements FlowBean
{
  private static final L10N L = new L10N(ResinProperties.class);
  private static final Logger log
    = Logger.getLogger(ResinProperties.class.getName());

  private Path _path;
  private FileSetType _fileSet;
  private boolean _isOptional;

  /**
   * Sets the resin:properties.
   */
  public void setPath(Path path)
  {
    if (path == null)
      throw new NullPointerException(L.l("'path' may not be null for resin:properties"));
    
    _path = path;
  }

  /**
   * Sets the resin:properties fileset.
   */
  public void setFileset(FileSetType fileSet)
  {
    _fileSet = fileSet;
  }
  
  /**
   * Sets true if the path is optional.
   */
  public void setOptional(boolean optional)
  {
    _isOptional = optional;
  }

  @PostConstruct
  public void init()
    throws Exception
  {
    if (_path == null) {
      if (_fileSet == null)
        throw new ConfigException(L.l("'path' attribute missing from resin:properties."));
    }
    else if (_path.canRead() && ! _path.isDirectory()) {
    }
    else if (_isOptional && ! _path.exists()) {
      log.finer(L.l("resin:properties '{0}' is not readable.", _path));

      Environment.addDependency(new Depend(_path));
      return;
    }
    else {
      throw new ConfigException(L.l("Required file '{0}' can not be read for resin:import.",
                                    _path.getNativePath()));
    }

    ArrayList<Path> paths;

    if (_fileSet != null)
      paths = _fileSet.getPaths();
    else {
      paths = new ArrayList<Path>();
      paths.add(_path);
    }

    for (int i = 0; i < paths.size(); i++) {
      Path path = paths.get(i);

      log.config(L.l("resin:properties '{0}'", path.getNativePath()));

      Environment.addDependency(new Depend(path));

      readProperties(path);
    }
  }
  
  private void readProperties(Path path)
  {
    ReadStream is = null;
    
    try {
      is = path.openRead();
      
      String line;
      
      while ((line = is.readLine()) != null) {
        line = line.trim();
        
        if (line.startsWith("#") || line.equals(""))
          continue;
        
        int p = line.indexOf(':');
        if (p < 0)
          throw new ConfigException(L.l("invalid line in {0}\n  {1}",
                                        path, line));
        
        String key = line.substring(0, p).trim();
        String value = line.substring(p + 1).trim();
        
        Config.setProperty(key, value);
      }
    } catch (IOException e) {
      log.log(Level.WARNING, e.toString(), e);
    } finally {
      IoUtil.close(is);
    }
  }
}
