/*
 * Copyright (c) 1998-2004 Caucho Technology -- all rights reserved
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

package com.caucho.jsp.java;

import java.io.*;
import java.util.*;
import java.util.logging.Level;

import java.lang.reflect.*;
import java.beans.*;

import javax.servlet.jsp.*;
import javax.servlet.jsp.tagext.*;

import com.caucho.vfs.*;
import com.caucho.util.*;
import com.caucho.xml.QName;

import com.caucho.jsp.*;

/**
 * Represents a custom tag.
 */
abstract public class GenericTag extends JspContainerNode {
  private static final String DEFAULT_VAR_TYPE = "java.lang.String";
  
  protected TagInstance _tag;
  protected TagInfo _tagInfo;
  protected Class _tagClass;
  protected VariableInfo []_varInfo;
  
  public GenericTag()
  {
  }
  
  public void setTagInfo(TagInfo tagInfo)
  {
    _tagInfo = tagInfo;
  }

  public TagInfo getTagInfo()
  {
    return _tagInfo;
  }

  public TagInstance getTag()
  {
    return _tag;
  }

  /**
   * Returns the tag name for the current tag.
   */
  public String getCustomTagName()
  {
    return _tag.getId();
  }

  /**
   * Returns true if the tag is a simple tag.
   */
  public boolean isSimple()
  {
    return _tag.isSimpleTag();
  }

  public void setTagClass(Class cl)
  {
    _tagClass = cl;
  }

  public VariableInfo []getVarInfo()
  {
    return _varInfo;
  }

  /**
   * Returns the body content.
   */
  public String getBodyContent()
  {
    return _tagInfo.getBodyContent();
  }
  
  /**
   * Adds a child node.
   */
  public void addChild(JspNode node)
    throws JspParseException
  {
    if (! "empty".equals(getBodyContent()))
      super.addChild(node);
    else if (node instanceof JspAttribute) {
      super.addChild(node);
    }
    else if (node instanceof StaticText &&
             ((StaticText) node).isWhitespace()) {
    }
    else {
      throw error(L.l("<{0}> must be empty.  Since <{0}> has a body-content of 'empty', it must not have any content.",
                      getTagName()));
    }
  }

  /**
   * Completes the element
   */
  public void endElement()
    throws Exception
  {
    if (_tagClass != null)
      _gen.addDepend(_tagClass);
	
    Hashtable<String,Object> tags = new Hashtable<String,Object>();

    for (int i = 0; i < _attributeNames.size(); i++) {
      QName qName = _attributeNames.get(i);
      Object value = _attributeValues.get(i);
      String name = qName.getName();

      if (value instanceof JspAttribute) {
	JspAttribute attr = (JspAttribute) value;

	if (attr.isStatic())
	  tags.put(name, attr.getStaticText());
	else
	  tags.put(name, TagData.REQUEST_TIME_VALUE);
      }
      else if (value instanceof String && hasRuntimeAttribute((String) value))
        tags.put(name, TagData.REQUEST_TIME_VALUE);
      else
        tags.put(name, value);

      TagAttributeInfo attrInfo = getAttributeInfo(qName);

      String typeName = null;

      boolean isFragment = false;
      Method method = getAttributeMethod(qName);
      
      Class type = null;

      if (method != null)
	type = method.getParameterTypes()[0];

      if (attrInfo != null) {
	typeName = attrInfo.getTypeName();
	isFragment = attrInfo.isFragment();

	if (isFragment &&
	    type != null && type.isAssignableFrom(JspFragment.class))
	  typeName = JspFragment.class.getName();
      }
      else if (method != null)
	typeName = type.getName();

      if (! isFragment && ! JspFragment.class.getName().equals(typeName)) {
      }
      else if (value instanceof JspAttribute) {
	JspAttribute jspAttr = (JspAttribute) value;

	jspAttr.setJspFragment(true);
      }
    }
    
    TagData tagData = new TagData(tags);
    
    _varInfo = _tagInfo.getVariableInfo(tagData);

    if (_varInfo == null)
      _varInfo = fillVariableInfo(_tagInfo.getTagVariableInfos(), tagData);

    TagExtraInfo tei = _tagInfo.getTagExtraInfo();
    ValidationMessage []messages;
    if (tei != null) {
      messages = tei.validate(tagData);

      _gen.addDepend(tei.getClass());

      if (messages != null && messages.length != 0) {
	throw error(messages[0].getMessage());
      }
    }
  }
  
  /**
   * True if the node has scripting
   */
  public boolean hasScripting()
  {
    if (super.hasScripting())
      return true;

    // Any conflicting values must be set each time.
    for (int i = 0; i < _attributeValues.size(); i++) {
      QName name = _attributeNames.get(i);
      Object value = _attributeValues.get(i);

      try {
	if (value instanceof String && hasRuntimeAttribute((String) value))
	  return true;
      } catch (Throwable e) {
	log.log(Level.WARNING, e.toString(), e);
	return true;
      }
    }
    
    return false;
  }
  
  /**
   * Generates code before the actual JSP.
   */
  public void generatePrologue(JspJavaWriter out)
    throws Exception
  {
    for (int i = 0; i < _attributeNames.size(); i++) {
      QName name = _attributeNames.get(i);
      Object value = _attributeValues.get(i);
      
      if (! (value instanceof JspFragmentNode))
	continue;
      
      JspFragmentNode frag = (JspFragmentNode) value;
      
      TagAttributeInfo attribute = getAttributeInfo(name);
      String typeName = null;

      boolean isFragment = false;

      if (attribute != null && attribute.isFragment())
	isFragment = true;

      String fragmentClass = JspFragment.class.getName();
      
      if (attribute != null && fragmentClass.equals(attribute.getTypeName()))
	isFragment = true;

      Method method = getAttributeMethod(name);

      if (method != null) {
	typeName = method.getParameterTypes()[0].getName();
	if (fragmentClass.equals(typeName))
	  isFragment = true;
      }

      if (isFragment)
	frag.generateFragmentPrologue(out);
    }
      
    TagInstance parent = getParent().getTag();

    boolean isBodyTag = BodyTag.class.isAssignableFrom(_tagClass);
    boolean isEmpty = isEmpty();
    boolean hasBodyContent = isBodyTag && ! isEmpty;
    
    _tag = parent.findTag(getQName(), _attributeNames,
			  hasBodyContent);

    if (_tag == null || ! _parseState.isRecycleTags()) {
      _tag = parent.addTag(getQName(), _tagInfo, _tagClass,
			   _attributeNames, _attributeValues,
			   hasBodyContent);

      if (! JspTagFileSupport.class.isAssignableFrom(_tagClass)) {
	out.printClass(_tagClass);
	out.println(" " + _tag.getId() + " = null;");
      }

      /*
      if (SimpleTag.class.isAssignableFrom(_tagClass) && hasCustomTag())
        out.println("javax.servlet.jsp.tagext.Tag " + _tag.getId() + "_adapter = null;");
      */
    }
    else {
      // Any conflicting values must be set each time.
      for (int i = 0; i < _attributeNames.size(); i++) {
        QName name = _attributeNames.get(i);
        Object value = _attributeValues.get(i);
        
        _tag.addAttribute(name, value);
      }
    }

    if (_tag == null)
      throw new NullPointerException();

    /* already taken care of
    if (! isEmpty())
      _tag.setBodyContent(true);
    */
      
    // Any AT_END variables
    for (int i = 0; _varInfo != null && i < _varInfo.length; i++) {
      VariableInfo var = _varInfo[i];

      if (var == null) {
      }
      else if (! _gen.hasScripting()) {
      }
      else if ((var.getScope() == VariableInfo.AT_END
                || var.getScope() == VariableInfo.AT_BEGIN)
               && var.getDeclare()
               && ! _gen.isDeclared(var.getVarName())) {
	String className = var.getClassName();

	if (className == null)
	  className = DEFAULT_VAR_TYPE;
	
        out.print(className + " " + var.getVarName() + " = ");

        _gen.addDeclared(var.getVarName());
        
        if ("byte".equals(var.getClassName()) ||
            "short".equals(var.getClassName()) ||
            "char".equals(var.getClassName()) ||
            "int".equals(var.getClassName()) ||
            "long".equals(var.getClassName()) ||
            "float".equals(var.getClassName()) ||
            "double".equals(var.getClassName()))
          out.println("0;");
        else if ("boolean".equals(var.getClassName()))
          out.println("false;");
        else
          out.println("null;");
      }
    }

    generatePrologueChildren(out);
  }

  /**
   * Generates the XML text representation for the tag validation.
   *
   * @param os write stream to the generated XML.
   */
  public void printXml(WriteStream os)
    throws IOException
  {
    TagInfo tag = getTagInfo();

    String name = tag.getTagLibrary().getPrefixString() + ':' + tag.getTagName();

    os.print("<" + name);
    
    printJspId(os);

    for (int i = 0; i < _attributeNames.size(); i++) {
      QName attrName = _attributeNames.get(i);
      Object value = _attributeValues.get(i);

      if (value instanceof String) {
	String string = (String) value;
	
	os.print(" " + attrName.getName() + "=\"");

	if (string.startsWith("<%=") && string.endsWith("%>")) {
	  os.print("%=");
	  os.print(xmlAttrText(string.substring(3, string.length() - 2)));
	  os.print("%");
	}
	else
	  os.print(xmlAttrText(string));
	
	os.print("\"");
      }
    }

    os.print(">");

    printXmlChildren(os);

    os.print("</" + name + ">");
  }

  /**
   * Generates the code for a custom tag.
   *
   * @param out the output writer for the generated java.
   */
  abstract public void generate(JspJavaWriter out)
    throws Exception;

  protected void fillAttributes(JspJavaWriter out, String name)
    throws Exception
  {
    TagAttributeInfo attrs[] = _tagInfo.getAttributes();

    // clear any attributes mentioned in the taglib that aren't set
    for (int i = 0; attrs != null && i < attrs.length; i++) {
      int p = getAttributeIndex(attrs[i].getName());
      
      if (p < 0 && attrs[i].isRequired()) {
	throw error(L.l("required attribute `{0}' missing from <{1}>",
                        attrs[i].getName(),
                        getTagName()));
      }
    }

    boolean isDynamic = DynamicAttributes.class.isAssignableFrom(_tagClass);
    
    // fill all mentioned attributes
    for (int i = 0; i < _attributeNames.size(); i++) {
      QName attrName = _attributeNames.get(i);
      Object value = _attributeValues.get(i);
      
      TagAttributeInfo attribute = getAttributeInfo(attrName);
      
      if (attrs != null && attribute == null && ! isDynamic)
	throw error(L.l("unexpected attribute `{0}' in <{1}>",
                        attrName.getName(), getTagName()));

      if (_tag.getAttribute(attrName) != null)
        continue;

      boolean isFragment = false;

      if (attribute != null) {
	isFragment = (attribute.isFragment() || 
		      attribute.getTypeName().equals(JspFragment.class.getName()));
      }

      if (value instanceof JspAttribute &&
	  ((JspAttribute) value).isJspFragment())
	isFragment = true;

      generateSetAttribute(out, name, attrName, value,
                           attribute == null || attribute.canBeRequestTime(),
			   isFragment);
    }
  }

  private TagAttributeInfo getAttributeInfo(QName attrName)
  {
    TagAttributeInfo attrs[] = _tagInfo.getAttributes();

    int j = 0;
    for (j = 0; attrs != null && j < attrs.length; j++) {
      if (isNameMatch(attrs[j].getName(), attrName))
	return attrs[j];
    }

    return null;
  }

  private int getAttributeIndex(String name)
  {
    for (int i = 0; i < _attributeNames.size(); i++) {
      QName attrName = _attributeNames.get(i);

      if (isNameMatch(name, attrName))
	return i;
    }

    return -1;
  }

  private boolean isNameMatch(String defName, QName attrName)
  {
    if (defName.equals(attrName.getName())) {
      return true;
    }
    else if (defName.equals(attrName.getLocalName()) &&
	     attrName.getPrefix().equals(getQName().getPrefix())) {
      return true;
    }
    else
      return false;
  }

  /**
   * Sets an attribute for a tag
   *
   * @param info the tag's introspected information
   * @param name the tag's Java variable name
   * @param attrName the attribute name to set
   * @param value the new value of the tag.
   */
  void generateSetAttribute(JspJavaWriter out,
                            String name, QName attrName, Object value,
                            boolean allowRtexpr, boolean isFragment)
    throws Exception
  {
    Method method = getAttributeMethod(attrName);

    boolean isDynamic = DynamicAttributes.class.isAssignableFrom(_tagClass);
    
    if (method != null) {
      // jsp/18cq
      if (Modifier.isStatic(method.getModifiers()))
	throw error(L.l("attribute '{0}' may not be a static method.",
			method.getName()));

      generateSetParameter(out, name, value, method,
			   allowRtexpr, "pageContext", isFragment);
    }
    else if (! isDynamic) {
      throw error(L.l("attribute `{0}' in tag `{1}' has no corresponding set method in tag class `{2}'",
                  attrName.getName(), getTagName(), _tagClass.getName()));
    }
    else if (isFragment) {
      String uri = attrName.getNamespaceURI();
      String local = attrName.getLocalName();

      out.print(name + ".setDynamicAttribute(");

      if (uri == null)
	out.print("null, ");
      else
	out.print("\"" + escapeJavaString(uri) + "\", ");
      
      JspFragmentNode frag = (JspFragmentNode) value;
      out.print("\"" + escapeJavaString(local) + "\", ");
      out.print(frag.generateValue());
      out.println(");");
    }
    else {
      String uri = attrName.getNamespaceURI();
      String local = attrName.getLocalName();
      
      out.print(name + ".setDynamicAttribute(");

      if (uri == null)
	out.print("null, ");
      else
	out.print("\"" + escapeJavaString(uri) + "\", ");
      
      out.print("\"" + escapeJavaString(local) + "\", ");
      out.print(generateRTValue(Object.class, value));
      out.println(");");
    }
  }

  private Method getAttributeMethod(QName attrName)
    throws Exception
  {
    Method method = null;
    
    try {
      BeanInfo info = Introspector.getBeanInfo(_tagClass);

      if (info != null)
	method = BeanUtil.getSetMethod(info, attrName.getLocalName());

      if (method != null)
	return method;
    } catch (Throwable e) {
      log.log(Level.FINER, e.toString(), e);
    }

    /*
    try {
      method = BeanUtil.getSetMethod(_tagClass, attrName.getLocalName());

      if (method != null)
	return method;
    } catch (Throwable e) {
      log.log(Level.FINER, e.toString(), e);
    }
    */

    return method;
  }

  /**
   * Returns true if there is a tag variable declaration matching the scope.
   */
  protected boolean hasVarDeclaration(int scope)
    throws Exception
  {
    for (int i = 0; _varInfo != null && i < _varInfo.length; i++) {
      VariableInfo var = _varInfo[i];
      
      if (var != null && var.getScope() == scope)
	return true;
    }

    return false;
  }

  /**
   * Prints a tag variable declaration.  Only the variables matching the
   * scope will be printed.
   *
   * @param out the stream to the java code.
   * @param scope the variable scope to print
   */
  protected void printVarDeclaration(JspJavaWriter out, int scope)
    throws Exception
  {
    for (int i = 0; _varInfo != null && i < _varInfo.length; i++) {
      VariableInfo var = _varInfo[i];
      
      if (var != null) {
        printVarDeclare(out, scope, var);
        printVarAssign(out, scope, var);
      }
    }
  }

  /**
   * Prints a tag variable declaration.  Only the variables matching the
   * scope will be printed.
   *
   * @param out the stream to the java code.
   * @param scope the variable scope to print
   */
  protected void printVarDeclare(JspJavaWriter out, int scope)
    throws Exception
  {
    for (int i = 0; _varInfo != null && i < _varInfo.length; i++) {
      VariableInfo var = _varInfo[i];

      if (var != null)
        printVarDeclare(out, scope, var);
    }
  }

  /**
   * Prints a tag variable declaration.  Only the variables matching the
   * scope will be printed.
   *
   * @param out the stream to the java code.
   * @param scope the variable scope to print
   */
  protected void printVarAssign(JspJavaWriter out, int scope)
    throws Exception
  {
    for (int i = 0; _varInfo != null && i < _varInfo.length; i++) {
      VariableInfo var = _varInfo[i];

      if (var != null)
        printVarAssign(out, scope, var);
    }
  }

  /**
   * Returns the VariableInfo corresponding the to tag vars and the tag
   * data.  Mainly, this means looking up the variable names from the
   * attributes for the name-from-attribute.
   *
   * @param tagVars the implicit tag variables for the tag
   * @param tagData the parsed tag attributes
   *
   * @return an array of filled VariableInfo
   */
  protected VariableInfo []fillVariableInfo(TagVariableInfo []tagVars,
                                            TagData tagData)
    throws JspParseException
  {
    if (tagVars == null)
      return null;

    VariableInfo []vars = new VariableInfo[tagVars.length];

    for (int i = 0; i < tagVars.length; i++) {
      TagVariableInfo tagVar = tagVars[i];

      String name = null;
      if (tagVar.getNameGiven() != null)
        name = tagVar.getNameGiven();
      else {
        String attributeName = tagVar.getNameFromAttribute();

        name = tagData.getAttributeString(attributeName);

        if (name == null)
          continue;
      }

      vars[i] = new VariableInfo(name, tagVar.getClassName(),
                                 tagVar.getDeclare(), tagVar.getScope());
    }

    return vars;
  }

  /**
   * Prints a tag variable declaration.  Only the variables matching the
   * scope will be printed.
   *
   * @param out the stream to the java code.
   * @param scope the variable scope to print
   */
  protected void printVarDeclare(JspJavaWriter out, int scope, VariableInfo var)
    throws Exception
  {
    if (! _gen.hasScripting() || var == null)
      return;
    
    if (var.getScope() == scope ||
        var.getScope() == VariableInfo.AT_BEGIN) {
      if (var.getVarName() == null)
        throw error(L.l("tag variable expects a name"));

      String className = var.getClassName();

      if (className == null)
	className = DEFAULT_VAR_TYPE;
      /*
      if (var.getClassName() == null)
        throw error(L.l("tag variable `{0}' expects a classname",
                        var.getVarName()));
      */

      validateVarName(var.getVarName());

      if (var.getDeclare() &&
          var.getScope() == scope &&
          var.getScope() == VariableInfo.NESTED &&
	  hasScripting() &&
          ! varAlreadyDeclared(var.getVarName()))
        out.println(className + " " + var.getVarName() + ";");
    }
  }

  /**
   * Prints a tag variable declaration.  Only the variables matching the
   * scope will be printed.
   *
   * @param out the stream to the java code.
   * @param scope the variable scope to print
   */
  protected void printVarAssign(JspJavaWriter out, int scope, VariableInfo var)
    throws Exception
  {
    if (var.getScope() == scope ||
        var.getScope() == VariableInfo.AT_BEGIN) {
      if (var.getVarName() == null)
        throw error(L.l("tag variable expects a name"));

      String className = var.getClassName();

      if (className == null || className.equals("null"))
	className = DEFAULT_VAR_TYPE;
      
      /*
      if (var.getClassName() == null)
        throw error(L.l("tag variable `{0}' expects a classname",
                        var.getVarName()));
      */

      validateVarName(var.getVarName());

      if (! _gen.hasScripting()) {
      }
      else if (var.getScope() != VariableInfo.NESTED || hasScripting()) {
	out.setLocation(_filename, _startLine);
	out.print(var.getVarName() + " = ");
	String v = "pageContext.findAttribute(\"" + var.getVarName() + "\")";
	convertParameterValue(out, className, v);
	out.println(";");
      }

      _gen.addBeanClass(var.getVarName(), className);
    }
  }

  private void validateVarName(String name)
    throws JspParseException
  {
    if (! Character.isJavaIdentifierStart(name.charAt(0)))
      throw error(L.l("tag variable `{0}' is an illegal Java identifier.", name));

    for (int i = 0; i < name.length(); i++) {
      if (! Character.isJavaIdentifierPart(name.charAt(i)))
        throw error(L.l("tag variable `{0}' is an illegal Java identifier.", name));
    }
  }

  /**
   * Returns true if the variable has been declared.
   */
  private boolean varAlreadyDeclared(String varName)
  {
    if (_gen.isDeclared(varName))
      return true;
    
    for (JspNode node = getParent(); node != null; node = node.getParent()) {
      if (! (node instanceof GenericTag))
        continue;

      GenericTag tag = (GenericTag) node;
      
      VariableInfo []varInfo = tag.getVarInfo();

      for (int i = 0; varInfo != null && i < varInfo.length; i++) {
        if (varInfo[i] == null)
          continue;
        else if (varInfo[i].getVarName().equals(varName))
          return true;
      }
    }

    return false;
  }

  /**
   * Returns true if the tag instance has been declared
   */
  protected boolean isDeclared()
  {
    if (! _gen.getRecycleTags())
      return false;

    JspNode parent = getParent();

    if (! (parent instanceof JspRoot) &&
	! (parent instanceof JspTop) &&
        ! (parent instanceof GenericTag) &&
        ! (parent instanceof JspAttribute))
      return false;

    boolean isDeclared = false;

    ArrayList<JspNode> siblings = getParent().getChildren();
    for (int i = 0; i < siblings.size(); i++) {
      JspNode node = siblings.get(i);

      if (node == this) {
        return isDeclared;
      }

      if (hasScriptlet(node)) {
        return false;
      }

      if (node instanceof GenericTag) {
        GenericTag customTag = (GenericTag) node;

        if (customTag.getTag() == getTag())
          isDeclared = true;
      }
    }

    return isDeclared;
  }

  /**
   * Returns true if the node or one of its children is a scriptlet
   */
  protected boolean hasScriptlet(JspNode node)
  {
    if (node instanceof JspScriptlet || node instanceof JspExpression)
      return true;

    ArrayList<JspNode> children = node.getChildren();

    if (children == null)
      return false;

    for (int i = 0; i < children.size(); i++) {
      JspNode child = children.get(i);

      if (hasScriptlet(child))
	return true;
    }

    return false;
  }
}
