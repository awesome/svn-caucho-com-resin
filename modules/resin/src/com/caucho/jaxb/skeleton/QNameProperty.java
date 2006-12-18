/*
 * Copyright (c) 1998-2006 Caucho Technology -- all rights reserved
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
 * @author Emil Ong
 */

package com.caucho.jaxb.skeleton;

import java.io.IOException;

import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.stream.XMLStreamException;

import com.caucho.util.L10N;

/**
 * a qname property
 */
public class QNameProperty extends Property {
  private static final L10N L = new L10N(QNameProperty.class);

  public static final QNameProperty PROPERTY = new QNameProperty();

  public void write(Marshaller m, XMLStreamWriter out, Object obj, QName qname)
    throws IOException, XMLStreamException, JAXBException
  {
    writeQNameStartElement(out, qname);

    if (obj != null) {
      QName name = (QName) obj;

      String namespace = name.getNamespaceURI();
      String prefix = name.getPrefix();

      // check if we need to declare this namespace prefix
      if (namespace != null && ! "".equals(namespace) &&
          prefix != null && ! "".equals(prefix)) {

        String declaredPrefix = out.getPrefix(namespace);
        
        if (prefix != null && ! prefix.equals(name.getPrefix()))
          out.writeNamespace(name.getPrefix(), name.getNamespaceURI());
      }

      if (name.getPrefix() == null || "".equals(name.getPrefix()))
        out.writeCharacters(name.getLocalPart());
      else
        out.writeCharacters(name.getPrefix() + ":" + name.getLocalPart());
    }

    writeQNameEndElement(out, qname);
  }

  // Can't use CDataProperty because we need access to the namespace map
  public Object read(Unmarshaller u, XMLStreamReader in, QName qname)
    throws IOException, XMLStreamException, JAXBException
  {
    if (in.getEventType() != in.START_ELEMENT || ! in.getName().equals(qname))
      throw new IOException(L.l("Expected <{0}>, not <{1}>", 
                                qname.getLocalPart(), in.getLocalName()));

    in.next();
 
    Object ret = null;

    if (in.getEventType() == in.CHARACTERS) {
      String text = in.getText();
      int colon = text.indexOf(':');

      if (colon < 0)
        ret = new QName(text);
      else {
        String prefix = text.substring(0, colon);

        String namespace = in.getNamespaceURI(prefix);

        if (namespace == null)
          throw new IOException(L.l("No known namespace for prefix {0}", 
                                    prefix));

        String localName = text.substring(colon + 1);

        if ("".equals(localName))
          throw new IOException(L.l("Malformed QName: empty localName"));

        ret = new QName(namespace, localName, prefix);
      }
    }

    while(in.getEventType() != in.END_ELEMENT)
      in.next();

    if (! in.getName().equals(qname))
      throw new IOException(L.l("Expected </{0}>, not </{1}>", 
                                qname.getLocalPart(), in.getLocalName()));

    in.next();

    return ret;
  }

  public String getSchemaType()
  {
    return "xsd:QName";
  }
}
