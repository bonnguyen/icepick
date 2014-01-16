package icepick.processor;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import static com.google.common.collect.FluentIterable.from;
import static com.google.common.collect.Multimaps.index;

class AnnotationsConverter {

  private final Logger logger;
  private final Elements elementUtils;
  private final Types typeUtils;

  AnnotationsConverter(Logger logger, Elements elementUtils, Types typeUtils) {
    this.logger = logger;
    this.elementUtils = elementUtils;
    this.typeUtils = typeUtils;
  }

  Map<EnclosingClass, Collection<AnnotatedField>> convert(Collection<? extends Element> annotatedElements) {
    FluentIterable<AnnotatedField> annotatedFields =
        from(annotatedElements).filter(new ValidModifier()).transform(new ToAnnotatedField());

    Set<TypeMirror> erasedEnclosingClasses =
        annotatedFields.transform(new ToErasedEnclosingClass()).toImmutableSet();

    return index(annotatedFields, new ByEnclosingClass(erasedEnclosingClasses)).asMap();
  }

  private class ValidModifier implements Predicate<Element> {
    @Override public boolean apply(@Nullable Element element) {
      boolean isValid = element.getModifiers().contains(Modifier.PRIVATE) ||
          element.getModifiers().contains(Modifier.STATIC) ||
          element.getModifiers().contains(Modifier.FINAL);

      if (!isValid) {
        logger.logError(element, "Field must not be private, static or final");
      }

      return isValid;
    }
  }

  private class ToAnnotatedField implements Function<Element, AnnotatedField> {
    @Nullable @Override public AnnotatedField apply(@Nullable Element element) {
      String name = element.getSimpleName().toString();
      TypeMirror type = element.asType();
      TypeElement enclosingClass = (TypeElement) element.getEnclosingElement();
      return new AnnotatedField(name, type, enclosingClass);
    }
  }

  private class ToErasedEnclosingClass implements Function<AnnotatedField, TypeMirror> {
    @Nullable @Override public TypeMirror apply(@Nullable AnnotatedField field) {
      return typeUtils.erasure(field.getEnclosingClassType().asType());
    }
  }

  private class ByEnclosingClass implements Function<AnnotatedField, EnclosingClass> {

    private final Set<TypeMirror> erasedEnclosingClasses;

    private ByEnclosingClass(Set<TypeMirror> erasedEnclosingClasses) {
      this.erasedEnclosingClasses = erasedEnclosingClasses;
    }

    @Nullable @Override public EnclosingClass apply(@Nullable AnnotatedField field) {
      TypeElement classType = field.getEnclosingClassType();
      String classPackage = elementUtils.getPackageOf(classType).getQualifiedName().toString();
      int packageLength = classPackage.length() + 1;
      String targetClass = classType.getQualifiedName().toString().substring(packageLength);
      String className = targetClass.replace(".", "$");
      String parentFqcn = findParentFqcn(classType);
      return new EnclosingClass(classPackage, className, targetClass, parentFqcn, classType);
    }

    private String findParentFqcn(TypeElement classType) {
      TypeMirror type;
      while (true) {
        type = classType.getSuperclass();
        if (type.getKind() == TypeKind.NONE) {
          return null;
        }
        classType = (TypeElement) ((DeclaredType) type).asElement();
        if (containsTypeMirror(type)) {
          return classType.getQualifiedName().toString();
        }
      }
    }

    private boolean containsTypeMirror(TypeMirror query) {
      // Ensure we are checking against a type-erased version for normalization purposes.
      TypeMirror erasure = typeUtils.erasure(query);
      for (TypeMirror mirror : erasedEnclosingClasses) {
        if (typeUtils.isSameType(mirror, erasure)) {
          return true;
        }
      }
      return false;
    }
  }
}
