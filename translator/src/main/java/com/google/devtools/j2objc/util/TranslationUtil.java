/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.j2objc.util;

import com.google.devtools.j2objc.Options;
import com.google.devtools.j2objc.ast.AbstractTypeDeclaration;
import com.google.devtools.j2objc.ast.ArrayAccess;
import com.google.devtools.j2objc.ast.ArrayCreation;
import com.google.devtools.j2objc.ast.ArrayInitializer;
import com.google.devtools.j2objc.ast.Assignment;
import com.google.devtools.j2objc.ast.CastExpression;
import com.google.devtools.j2objc.ast.ClassInstanceCreation;
import com.google.devtools.j2objc.ast.ConditionalExpression;
import com.google.devtools.j2objc.ast.EnumDeclaration;
import com.google.devtools.j2objc.ast.Expression;
import com.google.devtools.j2objc.ast.FieldAccess;
import com.google.devtools.j2objc.ast.FunctionInvocation;
import com.google.devtools.j2objc.ast.InfixExpression;
import com.google.devtools.j2objc.ast.MethodInvocation;
import com.google.devtools.j2objc.ast.NullLiteral;
import com.google.devtools.j2objc.ast.PackageDeclaration;
import com.google.devtools.j2objc.ast.ParenthesizedExpression;
import com.google.devtools.j2objc.ast.PostfixExpression;
import com.google.devtools.j2objc.ast.PrefixExpression;
import com.google.devtools.j2objc.ast.SimpleName;
import com.google.devtools.j2objc.ast.TreeNode;
import com.google.devtools.j2objc.ast.TreeUtil;
import com.google.devtools.j2objc.ast.Type;
import com.google.devtools.j2objc.ast.TypeDeclaration;
import com.google.devtools.j2objc.ast.TypeLiteral;
import com.google.devtools.j2objc.types.FunctionElement;
import com.google.devtools.j2objc.types.IOSMethodBinding;
import com.google.devtools.j2objc.types.Types;
import com.google.j2objc.annotations.ReflectionSupport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IMemberValuePairBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;

/**
 * General collection of utility methods.
 *
 * @author Keith Stanger
 */
public final class TranslationUtil {

  private final Types typeEnv;
  private final NameTable nameTable;

  public TranslationUtil(Types typeEnv, NameTable nameTable) {
    this.typeEnv = typeEnv;
    this.nameTable = nameTable;
  }

  public static ITypeBinding getSuperType(AbstractTypeDeclaration node) {
    // Use the AST as the source of truth where possible.
    if (node instanceof TypeDeclaration) {
      Type superType = ((TypeDeclaration) node).getSuperclassType();
      if (superType != null) {
        return superType.getTypeBinding();
      }
      return null;
    } else {
      return node.getTypeBinding().getSuperclass();
    }
  }

  public static List<ITypeBinding> getInterfaceTypes(AbstractTypeDeclaration node) {
    // Use the AST as the source of truth where possible.
    List<Type> astInterfaces = null;
    if (node instanceof TypeDeclaration) {
      astInterfaces = ((TypeDeclaration) node).getSuperInterfaceTypes();
    } else if (node instanceof EnumDeclaration) {
      astInterfaces = ((EnumDeclaration) node).getSuperInterfaceTypes();
    }
    if (astInterfaces == null) {  // AnnotationTypeDeclaration
      return Arrays.asList(node.getTypeBinding().getInterfaces());
    }
    List<ITypeBinding> result = new ArrayList<>();
    for (Type type : astInterfaces) {
      result.add(type.getTypeBinding());
    }
    return result;
  }

  public static boolean needsReflection(AbstractTypeDeclaration node) {
    return needsReflection(node.getTypeElement());
  }

  public static boolean needsReflection(PackageDeclaration node) {
    return needsReflection(getReflectionSupportLevel(
        ElementUtil.getAnnotation(node.getPackageElement(), ReflectionSupport.class)));
  }

  public static boolean needsReflection(TypeElement type) {
    if (ElementUtil.isLambda(type)) {
      return false;
    }
    while (type != null) {
      ReflectionSupport.Level level = getReflectionSupportLevel(
          ElementUtil.getAnnotation(type, ReflectionSupport.class));
      if (level != null) {
        return level == ReflectionSupport.Level.FULL;
      }
      type = ElementUtil.getDeclaringClass(type);
    }
    return !Options.stripReflection();
  }

  private static boolean needsReflection(ReflectionSupport.Level level) {
    if (level != null) {
      return level == ReflectionSupport.Level.FULL;
    } else {
      return !Options.stripReflection();
    }
  }

  public static ReflectionSupport.Level getReflectionSupportLevel(
      AnnotationMirror reflectionSupport) {
    if (reflectionSupport == null) {
      return null;
    }
    VariableElement level = (VariableElement)
        ElementUtil.getAnnotationValue(reflectionSupport, "value");
    return level != null
        ? ReflectionSupport.Level.valueOf(level.getSimpleName().toString()) : null;
  }

  /**
   * If possible give this expression an unbalanced extra retain. If a non-null
   * result is returned, then the returned expression has an unbalanced extra
   * retain and the passed in expression is removed from the tree and must be
   * discarded. If null is returned then the passed in expression is left
   * untouched. The caller must ensure the result is eventually consumed.
   */
  public static Expression retainResult(Expression node) {
    switch (node.getKind()) {
      case ARRAY_CREATION:
        ((ArrayCreation) node).setHasRetainedResult(true);
        return TreeUtil.remove(node);
      case CLASS_INSTANCE_CREATION:
        ((ClassInstanceCreation) node).setHasRetainedResult(true);
        return TreeUtil.remove(node);
      case FUNCTION_INVOCATION: {
        FunctionInvocation invocation = (FunctionInvocation) node;
        if (invocation.getFunctionElement().getRetainedResultName() != null) {
          invocation.setHasRetainedResult(true);
          return TreeUtil.remove(node);
        }
        return null;
      }
      case METHOD_INVOCATION: {
        MethodInvocation invocation = (MethodInvocation) node;
        Expression expr = invocation.getExpression();
        IMethodBinding method = invocation.getMethodBinding();
        if (expr != null && method instanceof IOSMethodBinding
            && ((IOSMethodBinding) method).getSelector().equals(NameTable.AUTORELEASE_METHOD)) {
          return TreeUtil.remove(expr);
        }
        return null;
      }
      default:
        return null;
    }
  }

  public static boolean isAssigned(Expression node) {
    TreeNode parent = node.getParent();

    while (parent instanceof ParenthesizedExpression) {
        parent = parent.getParent();
    }

    if (parent instanceof PostfixExpression) {
      PostfixExpression.Operator op = ((PostfixExpression) parent).getOperator();
      if (op == PostfixExpression.Operator.INCREMENT
          || op == PostfixExpression.Operator.DECREMENT) {
        return true;
      }
    } else if (parent instanceof PrefixExpression) {
      PrefixExpression.Operator op = ((PrefixExpression) parent).getOperator();
      if (op == PrefixExpression.Operator.INCREMENT || op == PrefixExpression.Operator.DECREMENT
          || op == PrefixExpression.Operator.ADDRESS_OF) {
        return true;
      }
    } else if (parent instanceof Assignment) {
      return node == ((Assignment) parent).getLeftHandSide();
    }
    return false;
  }

  /**
   * Reterns whether the expression might have any side effects. If true, it
   * would be unsafe to prune the given node from the tree.
   */
  public static boolean hasSideEffect(Expression expr) {
    IVariableBinding var = TreeUtil.getVariableBinding(expr);
    if (var != null && BindingUtil.isVolatile(var)) {
      return true;
    }
    switch (expr.getKind()) {
      case BOOLEAN_LITERAL:
      case CHARACTER_LITERAL:
      case NULL_LITERAL:
      case NUMBER_LITERAL:
      case QUALIFIED_NAME:
      case SIMPLE_NAME:
      case STRING_LITERAL:
      case SUPER_FIELD_ACCESS:
      case THIS_EXPRESSION:
        return false;
      case CAST_EXPRESSION:
        return hasSideEffect(((CastExpression) expr).getExpression());
      case CONDITIONAL_EXPRESSION:
        {
          ConditionalExpression condExpr = (ConditionalExpression) expr;
          return hasSideEffect(condExpr.getExpression())
              || hasSideEffect(condExpr.getThenExpression())
              || hasSideEffect(condExpr.getElseExpression());
        }
      case FIELD_ACCESS:
        return hasSideEffect(((FieldAccess) expr).getExpression());
      case INFIX_EXPRESSION:
        for (Expression operand : ((InfixExpression) expr).getOperands()) {
          if (hasSideEffect(operand)) {
            return true;
          }
        }
        return false;
      case PARENTHESIZED_EXPRESSION:
        return hasSideEffect(((ParenthesizedExpression) expr).getExpression());
      case PREFIX_EXPRESSION:
        {
          PrefixExpression preExpr = (PrefixExpression) expr;
          PrefixExpression.Operator op = preExpr.getOperator();
          return op == PrefixExpression.Operator.INCREMENT
              || op == PrefixExpression.Operator.DECREMENT
              || hasSideEffect(preExpr.getOperand());
        }
      default:
        return true;
    }
  }

  /**
   * Returns the modifier for an assignment expression being converted to a
   * function. The result will be "Array" if the lhs is an array access,
   * "Strong" if the lhs is a field with a strong reference, and an empty string
   * for local variables and weak fields.
   */
  public static String getOperatorFunctionModifier(Expression expr) {
    IVariableBinding var = TreeUtil.getVariableBinding(expr);
    if (var == null) {
      assert TreeUtil.trimParentheses(expr) instanceof ArrayAccess
          : "Expression cannot be resolved to a variable or array access.";
      return "Array";
    }
    String modifier = "";
    if (BindingUtil.isVolatile(var)) {
      modifier += "Volatile";
    }
    if (!BindingUtil.isWeakReference(var) && (var.isField() || Options.useARC())) {
      modifier += "Strong";
    }
    return modifier;
  }

  public Expression createObjectArray(List<Expression> expressions, ITypeBinding arrayType) {
    if (expressions.isEmpty()) {
      return new ArrayCreation(arrayType, typeEnv, 0);
    }
    ArrayCreation creation = new ArrayCreation(arrayType, typeEnv);
    ArrayInitializer initializer = new ArrayInitializer(arrayType);
    initializer.getExpressions().addAll(expressions);
    creation.setInitializer(initializer);
    return creation;
  }

  public Expression createAnnotation(IAnnotationBinding annotationBinding) {
    ITypeBinding annotationType = annotationBinding.getAnnotationType();
    FunctionElement element = new FunctionElement(
        "create_" + nameTable.getFullName(annotationType), annotationType, annotationType);
    FunctionInvocation invocation = new FunctionInvocation(element, annotationType);
    for (IMemberValuePairBinding valueBinding :
         BindingUtil.getSortedMemberValuePairs(annotationBinding)) {
      ITypeBinding valueType = valueBinding.getMethodBinding().getReturnType();
      element.addParameters(valueType);
      invocation.addArgument(createAnnotationValue(valueType, valueBinding.getValue()));
    }
    return invocation;
  }

  public Expression createAnnotationValue(ITypeBinding type, Object value) {
    if (value == null) {
      return new NullLiteral();
    } else if (value instanceof IVariableBinding) {
      return new SimpleName((IVariableBinding) value);
    } else if (value instanceof ITypeBinding) {
      return new TypeLiteral((ITypeBinding) value, typeEnv);
    } else if (value instanceof IAnnotationBinding) {
      return createAnnotation((IAnnotationBinding) value);
    } else if (value instanceof Object[]) {
      Object[] array = (Object[]) value;
      List<Expression> generatedValues = new ArrayList<>();
      for (Object elem : array) {
        generatedValues.add(createAnnotationValue(type.getComponentType(), elem));
      }
      return createObjectArray(generatedValues, type);
    } else {  // Boolean, Character, Number, String
      return TreeUtil.newLiteral(value, typeEnv);
    }
  }
}
