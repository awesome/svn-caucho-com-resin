/*
 * Copyright (c) 1998-2008 Caucho Technology -- all rights reserved
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

package com.caucho.config.inject;

import com.caucho.config.annotation.ServiceType;
import com.caucho.config.program.FieldComponentProgram;
import com.caucho.config.*;
import com.caucho.config.j2ee.*;
import com.caucho.config.program.Arg;
import com.caucho.config.program.ConfigProgram;
import com.caucho.config.program.ContainerProgram;
import com.caucho.config.scope.ScopeContext;
import com.caucho.config.types.*;
import com.caucho.naming.*;
import com.caucho.util.*;
import com.caucho.config.*;
import com.caucho.config.bytecode.*;
import com.caucho.config.cfg.*;
import com.caucho.config.event.ObserverImpl;
import com.caucho.config.inject.AnnotatedTypeImpl;
import com.caucho.config.program.BeanArg;

import java.lang.reflect.*;
import java.lang.annotation.*;
import java.util.*;
import java.util.logging.*;
import java.io.*;

import javax.annotation.*;
import javax.enterprise.context.Dependent;
import javax.enterprise.context.NormalScope;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.AnnotationLiteral;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.Stereotype;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Annotated;
import javax.enterprise.inject.spi.AnnotatedConstructor;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedParameter;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.PassivationCapable;
import javax.inject.Named;
import javax.inject.Qualifier;
import javax.inject.Scope;

/**
 * Common bean introspection for Produces and ManagedBean.
 */
//  implements ObjectProxy
public class AbstractIntrospectedBean<T> extends AbstractBean<T>
  implements PassivationCapable
{
  private static final L10N L = new L10N(AbstractIntrospectedBean.class);
  private static final Logger log
    = Logger.getLogger(AbstractIntrospectedBean.class.getName());

  private static final Object []NULL_ARGS = new Object[0];
  private static final ConfigProgram []NULL_INJECT = new ConfigProgram[0];

  private static final HashSet<Class> _reservedTypes
    = new HashSet<Class>();

  public static final Annotation []CURRENT_ANN
    = new Annotation[] { new CurrentLiteral() };

  // AnnotatedType for ManagedBean, AnnotatedMethod for produces
  private Annotated _annotated;

  private Type _type;
  private BaseType _baseType;

  private LinkedHashSet<BaseType> _types
    = new LinkedHashSet<BaseType>();

  private LinkedHashSet<Type> _typeClasses
    = new LinkedHashSet<Type>();

  private ArrayList<Annotation> _qualifiers
    = new ArrayList<Annotation>();

  private Class<? extends Annotation> _scope;

  private ArrayList<Annotation> _stereotypes
    = new ArrayList<Annotation>();

  private String _name;

  private String _passivationId;

  private boolean _isNullable;

  // protected ScopeContext _scope;

  public AbstractIntrospectedBean(InjectManager manager,
                                  Type type,
                                  Annotated annotated)
  {
    super(manager);
    _annotated = annotated;

    _type = type;

    if (type instanceof Class) {
      // ioc/024d
      _baseType = manager.createClassBaseType((Class) type);
    }
    else
      _baseType = manager.createBaseType(type);
  }

  public BaseType getBaseType()
  {
    return _baseType;
  }

  public Class getBeanClass()
  {
    return _baseType.getRawClass();
  }

  public Type getTargetType()
  {
    return _baseType.toType();
  }

  public String getTargetSimpleName()
  {
    return _baseType.getSimpleName();
  }

  public String getTargetName()
  {
    return _baseType.toString();
  }

  public Class getTargetClass()
  {
    return _baseType.getRawClass();
  }

  @Override
  public Annotated getAnnotated()
  {
    return _annotated;
  }

  /*
  protected AnnotatedType getAnnotatedType()
  {
    return  new BeanTypeImpl(getTargetType(), getIntrospectionClass());
  }
  */

  protected Class getIntrospectionClass()
  {
    return getTargetClass();
  }

  /**
   * Gets the bean's EL qualifier name.
   */
  public String getName()
  {
    return _name;
  }

  /**
   * Returns the bean's qualifier types
   */
  public Set<Annotation> getQualifiers()
  {
    Set<Annotation> set = new LinkedHashSet<Annotation>();

    for (Annotation qualifier : _qualifiers) {
      set.add(qualifier);
    }

    return set;
  }

  public String getId()
  {
    if (_passivationId == null)
      _passivationId = calculatePassivationId();

    return _passivationId;
  }

  /**
   * Returns the bean's stereotypes
   */
  public Set<Class<? extends Annotation>> getStereotypes()
  {
    Set<Class<? extends Annotation>> set
      = new LinkedHashSet<Class<? extends Annotation>>();

    for (Annotation stereotype : _stereotypes) {
      set.add(stereotype.annotationType());
    }

    return set;
  }

  /**
   * Returns an array of the qualifier annotations
   */
  public Annotation []getQualifierArray()
  {
    if (_qualifiers == null || _qualifiers.size() == 0)
      return new Annotation[] { new CurrentLiteral() };

    Annotation []qualifiers = new Annotation[_qualifiers.size()];
    _qualifiers.toArray(qualifiers);

    return qualifiers;
  }

  /**
   * Returns the scope
   */
  public Class<? extends Annotation> getScope()
  {
    return _scope;
  }

  /**
   * Returns the types that the bean implements
   */
  public Set<Type> getTypes()
  {
    return _typeClasses;
  }

  /**
   * Returns the types that the bean implements
   */
  public Set<BaseType> getGenericTypes()
  {
    return _types;
  }

  /**
   * Introspects all the types implemented by the class
   */
  protected void introspectTypes(Type type)
  {
    introspectTypes(type, null);
  }

  /**
   * Introspects all the types implemented by the class
   */
  private void introspectTypes(Type type, HashMap paramMap)
  {
    if (type == null || _reservedTypes.contains(type))
      return;

    BaseType baseType = addType(type, paramMap);

    if (baseType == null)
      return;

    HashMap newParamMap = baseType.getParamMap();
    Class cl = baseType.getRawClass();

    introspectTypes(cl.getGenericSuperclass(), newParamMap);

    for (Type iface : cl.getGenericInterfaces()) {
      introspectTypes(iface, newParamMap);
    }
  }

  protected BaseType addType(Type type, HashMap paramMap)
  {
    BaseType baseType = BaseType.create(type, paramMap);

    if (baseType == null)
      return null;

    if (_types.contains(baseType))
      return null;

    _types.add(baseType);

    /*
    if (! _typeClasses.contains(baseType.getRawClass()))
      _typeClasses.add(baseType.getRawClass());
    */
    if (! _typeClasses.contains(baseType.toType()))
      _typeClasses.add(baseType.toType());

    return baseType;
  }

  public void introspect()
  {
    super.introspect();

    introspectTypes(_baseType.toType());
    introspect(_annotated);
  }

  protected void introspect(Annotated annotated)
  {
    introspectScope(annotated);
    introspectQualifiers(annotated);
    introspectName(annotated);
    introspectStereotypes(annotated);

    introspectDefault();
  }

  /**
   * Called for implicit introspection.
   */
  protected void introspectScope(Annotated annotated)
  {
    BeanManager inject = getBeanManager();

    for (Annotation ann : annotated.getAnnotations()) {
      if (inject.isScope(ann.annotationType())) {
        if (_scope != null && _scope != ann.annotationType())
          throw new ConfigException(L.l("{0}: @Scope annotation @{1} conflicts with @{2}.  Java Injection components may only have a single @Scope.",
                                        getTargetName(),
                                        _scope.getName(),
                                        ann.annotationType().getName()));

        _scope = ann.annotationType();
      }
    }
  }

  /**
   * Introspects the qualifier annotations
   */
  protected void introspectQualifiers(Annotated annotated)
  {
    BeanManager inject = getBeanManager();

    for (Annotation ann : annotated.getAnnotations()) {
      if (inject.isQualifier(ann.annotationType())) {
        if (ann instanceof Named) {
          Named named = (Named) ann;

          if ("".equals(named.value())) {
            ann = Names.create(getDefaultName());
          }
        }

        _qualifiers.add(ann);
      }
    }
  }

  /**
   * Introspects the qualifier annotations
   */
  protected void introspectName(Annotated annotated)
  {
    Annotation ann = annotated.getAnnotation(Named.class);

    if (ann != null) {
      String value = null;

      try {
        // ioc/0m04
        Method m = ann.getClass().getMethod("value", new Class[0]);
        value = (String) m.invoke(ann);
      } catch (Exception e) {
        log.log(Level.FINE, e.toString(), e);
      }

      if (value == null)
        value = "";

      _name = value;
    }
  }

  /**
   * Adds the stereotypes from the bean's annotations
   */
  protected void introspectStereotypes(Annotated annotated)
  {
    for (Annotation stereotype : annotated.getAnnotations()) {
      Class stereotypeType = stereotype.annotationType();

      if (stereotypeType.isAnnotationPresent(Stereotype.class))
        _stereotypes.add(stereotype);

      for (Annotation ann : stereotypeType.getDeclaredAnnotations()) {
        Class annType = ann.annotationType();

        if (_scope == null
            && (annType.isAnnotationPresent(Scope.class)
                || annType.isAnnotationPresent(NormalScope.class)))
          _scope = annType;

        if (annType.equals(Named.class) && _name == null) {
          Named named = (Named) ann;
          _name = "";

          if (! "".equals(named.value()))
            throw new ConfigException(L.l("@Named must not have a value in a @Stereotype definition, because @Stereotypes are used with multiple beans."));
        }

        if (annType.isAnnotationPresent(Qualifier.class)
            && ! annType.equals(Named.class)) {
          throw new ConfigException(L.l("'{0}' is not allowed on @Stereotype '{1}' because stereotypes may not have @Qualifier annotations",
                                        ann, stereotype));
        }
      }
    }
  }

  protected void introspectDefault()
  {
    if (_qualifiers.size() == 0)
      _qualifiers.add(CurrentLiteral.CURRENT);

    if (_scope == null)
      _scope = Dependent.class;

    if ("".equals(_name))
      _name = getDefaultName();
  }

  protected String getDefaultName()
  {
    String name = getTargetSimpleName();

    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  protected void bind()
  {
  }

  /**
   * Returns true if the bean can be null
   */
  public boolean isNullable()
  {
    return false;
  }

  /**
   * Returns true if the bean is serializable
   */
  public boolean isPassivationCapable()
  {
    return Serializable.class.isAssignableFrom(getTargetClass());
  }

  /**
   * Instantiate the bean.
   */
  public T create(CreationalContext<T> env)
  {
    throw new UnsupportedOperationException(getClass().getName());
  }

  /**
   * Call destroy
   */
  public void destroy(T instance, CreationalContext<T> env)
  {
  }

  /**
   * Call destroy
   */
  public void destroy(T instance)
  {
  }

  /**
   * Inject the bean.
   */
/*
  public void inject(T instance)
  {
  }
*/

  /**
   * Call post-construct
   */
/*
  public void postConstruct(T instance)
  {
  }
*/

  /**
   * Call pre-destroy
   */
/*
  public void preDestroy(T instance)
  {
  }
*/

  public void dispose(T instance)
  {
  }

  /**
   * Returns the set of injection points, for validation.
   */
  public Set<InjectionPoint> getInjectionPoints()
  {
    return new HashSet<InjectionPoint>();
  }

  public String toDebugString()
  {
    StringBuilder sb = new StringBuilder();

    sb.append(getTargetSimpleName());
    sb.append("[");

    if (_name != null) {
      sb.append("name=");
      sb.append(_name);
    }

    for (Annotation qualifier : _qualifiers) {
      sb.append(",");
      sb.append(qualifier);
    }

    if (_scope != null && _scope != Dependent.class) {
      sb.append(", @");
      sb.append(_scope.getSimpleName());
    }

    sb.append("]");

    return sb.toString();
  }

  static class MethodNameComparator implements Comparator<AnnotatedMethod> {
    public int compare(AnnotatedMethod a, AnnotatedMethod b)
    {
      return a.getJavaMember().getName().compareTo(b.getJavaMember().getName());
    }
  }

  static class AnnotationComparator implements Comparator<Annotation> {
    public int compare(Annotation a, Annotation b)
    {
      Class annTypeA = a.annotationType();
      Class annTypeB = b.annotationType();

      return annTypeA.getName().compareTo(annTypeB.getName());
    }
  }

  static {
    _reservedTypes.add(java.io.Closeable.class);
    _reservedTypes.add(java.io.Serializable.class);
    _reservedTypes.add(Cloneable.class);
    _reservedTypes.add(Object.class);
    _reservedTypes.add(Comparable.class);
  }
}
