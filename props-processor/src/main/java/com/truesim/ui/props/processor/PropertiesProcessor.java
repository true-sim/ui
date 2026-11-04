package com.truesim.ui.props.processor;

import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleAnnotationValueVisitor14;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * The annotation processor of {@code ui-props} (design 5.11, UI19 (a)): for each record or class carrying a property
 * annotation ({@code com.truesim.ui.props.annotations.*}) on a member, or {@code @Inspectable} itself, it writes a
 * {@code <Type>Properties} class beside it (a nested type's names joined by {@code _}) holding:
 * <ul>
 * <li>a {@code Property} constant per property, its getter and wither (records: the canonical constructor) or
 * setter as direct calls, with its element and key types and its runtime annotations rebuilt through
 * {@code Hints.annotation} (custom annotations too);
 * <li>{@code MODEL}, the type's {@code PropertyModel}: its properties, annotations, factory ({@code DEFAULTS}, a
 * no-argument constructor, else defaults) and a sealed type's permitted subtypes;
 * <li>direct static accessors ({@code name(o)}, {@code withName(o, v)}).
 * </ul>
 * {@code PropertyModels} finds the class by name, so no reflection runs on the type at run time. Types the generated
 * class could not see (private ones, inside private ones) and generic types are skipped with a note; the reflection
 * fallback serves them. JDK only: it reads the annotations by name, so it needs no {@code ui-props} on its path.
 */
@SupportedAnnotationTypes("com.truesim.ui.props.annotations.*")
public final class PropertiesProcessor extends AbstractProcessor {

    static final String ANNOTATIONS = "com.truesim.ui.props.annotations.";
    static final String PROPS = "com.truesim.ui.props.";

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        Set<TypeElement> types = new LinkedHashSet<>();
        for (TypeElement a : annotations) {
            for (Element e : round.getElementsAnnotatedWith(a)) {
                TypeElement t = owner(e);
                if (t != null) {
                    types.add(t);
                }
            }
        }
        Set<TypeElement> sealed = new LinkedHashSet<>();
        for (TypeElement t : types) {
            List<TypeMirror> supers = new ArrayList<>(t.getInterfaces());
            supers.add(t.getSuperclass());
            for (TypeMirror m : supers) {
                if (m.getKind() == TypeKind.DECLARED) {
                    TypeElement s = (TypeElement) ((DeclaredType) m).asElement();
                    if (s.getModifiers().contains(Modifier.SEALED) && !types.contains(s)
                        && processingEnv.getElementUtils().getTypeElement(s.getQualifiedName()) != null
                        && round.getRootElements().contains(outermost(s))) {
                        sealed.add(s); // a sealed parent of an annotated type: its subtypes for add choosers
                    }
                }
            }
        }
        types.addAll(sealed);
        for (TypeElement t : types) {
            generate(t);
        }
        return false;
    }

    private static Element outermost(Element e) {
        Element o = e;
        while (o.getEnclosingElement() instanceof TypeElement t) {
            o = t;
        }
        return o;
    }

    /** The record or class an annotated element belongs to (itself, for a type). */
    private static TypeElement owner(Element e) {
        Element o = e;
        while (o != null && !(o.getKind() == ElementKind.RECORD || o.getKind() == ElementKind.CLASS
            || o.getKind() == ElementKind.INTERFACE || o.getKind() == ElementKind.ENUM
            || o.getKind() == ElementKind.ANNOTATION_TYPE)) {
            o = o.getEnclosingElement();
        }
        if (o == null || o.getKind() == ElementKind.ENUM || o.getKind() == ElementKind.ANNOTATION_TYPE) {
            return null;
        }
        return (TypeElement) o;
    }

    private void generate(TypeElement type) {
        Messager log = processingEnv.getMessager();
        for (Element e = type; e instanceof TypeElement t; e = e.getEnclosingElement()) {
            if (t.getModifiers().contains(Modifier.PRIVATE) || t.getNestingKind() == NestingKind.LOCAL
                || t.getNestingKind() == NestingKind.ANONYMOUS) {
                log.printMessage(Diagnostic.Kind.NOTE, "ui-props: " + type + " is not visible to a generated class;"
                    + " its properties are read by reflection", type);
                return;
            }
        }
        if (!type.getTypeParameters().isEmpty()) {
            log.printMessage(Diagnostic.Kind.NOTE, "ui-props: " + type + " is generic; its properties are read by"
                + " reflection", type);
            return;
        }
        Elements elements = processingEnv.getElementUtils();
        PackageElement pkg = elements.getPackageOf(type);
        String packageName = pkg.isUnnamed() ? "" : pkg.getQualifiedName().toString();
        String simple = type.getQualifiedName().toString().substring(packageName.isEmpty() ? 0
            : packageName.length() + 1).replace('.', '_') + "Properties";
        String typeName = type.getQualifiedName().toString();
        try (Writer w = processingEnv.getFiler().createSourceFile((packageName.isEmpty() ? "" : packageName + ".")
            + simple, type).openWriter()) {
            w.write(new Writer0(type, typeName, packageName, simple).source());
        } catch (IOException e) {
            log.printMessage(Diagnostic.Kind.ERROR, "ui-props: cannot write " + simple + ": " + e.getMessage(), type);
        }
    }

    /** One property found on a type. */
    private record Prop(String name, TypeMirror type, String get, String set, boolean component, Element source,
        List<Element> annotated) {
    }

    /** Writes the source of one generated class. */
    private final class Writer0 {
        private final TypeElement type;
        private final String typeName;
        private final String packageName;
        private final String simple;
        private final Types types = processingEnv.getTypeUtils();
        private final Elements elements = processingEnv.getElementUtils();
        private final StringBuilder out = new StringBuilder();

        Writer0(TypeElement type, String typeName, String packageName, String simple) {
            this.type = type;
            this.typeName = typeName;
            this.packageName = packageName;
            this.simple = simple;
        }

        String source() {
            List<Prop> props = type.getKind() == ElementKind.RECORD ? recordProps() : beanProps();
            if (!packageName.isEmpty()) {
                out.append("package ").append(packageName).append(";\n\n");
            }
            out.append("/** Property metadata of {@link ").append(typeName)
                .append("}, generated by the ui-props annotation processor. */\n");
            out.append("@javax.annotation.processing.Generated(\"").append(PropertiesProcessor.class.getName())
                .append("\")\n");
            out.append("@SuppressWarnings({\"unchecked\", \"rawtypes\"})\n");
            out.append("public final class ").append(simple).append(" {\n\n");
            out.append("    private ").append(simple).append("() {\n    }\n");
            for (int i = 0; i < props.size(); i++) {
                property(props, i);
            }
            model(props);
            for (int i = 0; i < props.size(); i++) {
                accessors(props, i);
            }
            out.append("}\n");
            return out.toString();
        }

        // ------------------------------------------------------------ finding properties

        private List<Prop> recordProps() {
            List<Prop> list = new ArrayList<>();
            for (RecordComponentElement c : type.getRecordComponents()) {
                String n = c.getSimpleName().toString();
                List<Element> annotated = new ArrayList<>();
                annotated.add(c);
                ExecutableElement accessor = c.getAccessor();
                if (accessor != null) {
                    annotated.add(accessor);
                }
                for (VariableElement f : ElementFilter.fieldsIn(type.getEnclosedElements())) {
                    if (f.getSimpleName().contentEquals(n)) {
                        annotated.add(f);
                    }
                }
                list.add(new Prop(n, c.asType(), "o." + n + "()", null, true, c, annotated));
            }
            return list;
        }

        private List<Prop> beanProps() {
            List<Prop> list = new ArrayList<>();
            List<TypeElement> chain = new ArrayList<>();
            for (TypeElement t = type; t != null && !t.getQualifiedName().contentEquals("java.lang.Object");
                t = superclass(t)) {
                chain.addFirst(t);
            }
            List<String> seen = new ArrayList<>();
            for (TypeElement t : chain) {
                for (VariableElement f : ElementFilter.fieldsIn(t.getEnclosedElements())) {
                    String n = f.getSimpleName().toString();
                    if (f.getModifiers().contains(Modifier.STATIC) || seen.contains(n)) {
                        continue;
                    }
                    String cap = Character.toUpperCase(n.charAt(0)) + n.substring(1);
                    ExecutableElement getter = method(f.asType().getKind() == TypeKind.BOOLEAN ? "is" + cap
                        : "get" + cap, 0);
                    if (getter == null) {
                        getter = method("get" + cap, 0);
                    }
                    ExecutableElement setter = method("set" + cap, 1);
                    List<Element> annotated = new ArrayList<>();
                    annotated.add(f);
                    if (getter != null) {
                        annotated.add(getter);
                        if (setter != null) {
                            annotated.add(setter);
                        }
                        list.add(new Prop(n, f.asType(), "o." + getter.getSimpleName() + "()", setter == null ? null
                            : "o." + setter.getSimpleName() + "(%s)", false, f, annotated));
                    } else if (visible(f) && f.getModifiers().contains(Modifier.PUBLIC)) {
                        list.add(new Prop(n, f.asType(), "o." + n, f.getModifiers().contains(Modifier.FINAL) ? null
                            : "o." + n + " = %s", false, f, annotated));
                    } else {
                        continue;
                    }
                    seen.add(n);
                }
            }
            return list;
        }

        private TypeElement superclass(TypeElement t) {
            TypeMirror s = t.getSuperclass();
            return s.getKind() == TypeKind.DECLARED ? (TypeElement) ((DeclaredType) s).asElement() : null;
        }

        private boolean visible(Element e) {
            return !e.getModifiers().contains(Modifier.PRIVATE);
        }

        private ExecutableElement method(String name, int parameters) {
            for (ExecutableElement m : ElementFilter.methodsIn(elements.getAllMembers(type))) {
                if (m.getSimpleName().contentEquals(name) && m.getParameters().size() == parameters
                    && m.getModifiers().contains(Modifier.PUBLIC) && !m.getModifiers().contains(Modifier.STATIC)) {
                    return m;
                }
            }
            return null;
        }

        // ------------------------------------------------------------ writing

        private String boxed(TypeMirror t) {
            return t.getKind().isPrimitive() ? types.boxedClass((javax.lang.model.type.PrimitiveType) t)
                .getQualifiedName().toString() : t.toString();
        }

        private String erased(TypeMirror t) {
            return t.getKind().isPrimitive() ? t.toString() : types.erasure(t).toString();
        }

        /** The value {@code v} for the property type: unboxed with a zero for null when primitive. */
        private String value(TypeMirror t) {
            return switch (t.getKind()) {
                case BOOLEAN -> "(v != null && v)";
                case CHAR -> "(v == null ? '\\0' : v)";
                case INT -> "(v == null ? 0 : v)";
                case LONG -> "(v == null ? 0L : v)";
                case FLOAT -> "(v == null ? 0f : v)";
                case DOUBLE -> "(v == null ? 0d : v)";
                case SHORT, BYTE -> "(v == null ? (" + t + ") 0 : v)";
                default -> "v";
            };
        }

        private String constant(String name) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (Character.isUpperCase(c) && i > 0 && !Character.isUpperCase(name.charAt(i - 1))) {
                    b.append('_');
                }
                b.append(Character.toUpperCase(c));
            }
            return b.toString().equals("MODEL") ? "MODEL_PROPERTY" : b.toString();
        }

        private String wither(List<Prop> props, int index, boolean boxed) {
            StringBuilder b = new StringBuilder("new ").append(typeName).append('(');
            for (int j = 0; j < props.size(); j++) {
                if (j > 0) {
                    b.append(", ");
                }
                b.append(j != index ? props.get(j).get() : boxed ? value(props.get(j).type()) : "v");
            }
            return b.append(')').toString();
        }

        private void property(List<Prop> props, int i) {
            Prop p = props.get(i);
            String vt = boxed(p.type());
            out.append("\n    /** {@code ").append(p.name()).append("}. */\n");
            out.append("    public static final ").append(PROPS).append("Property<").append(typeName).append(", ")
                .append(vt).append("> ").append(constant(p.name())).append(" =\n");
            out.append("        ").append(PROPS).append("Property.<").append(typeName).append(", ").append(vt)
                .append(">builder(\"").append(p.name()).append("\", ").append(erased(p.type())).append(".class)\n");
            out.append("            .getter(o -> ").append(p.get()).append(")\n");
            if (p.component()) {
                out.append("            .wither((o, v) -> ").append(wither(props, i, true)).append(")\n");
            } else if (p.set() != null) {
                out.append("            .setter((o, v) -> ").append(p.set().replace("%s", value(p.type())))
                    .append(")\n");
            }
            generics(p.type());
            String annotations = annotations(p.annotated());
            if (!annotations.isEmpty()) {
                out.append("            .annotations(").append(annotations).append(")\n");
            }
            out.append("            .index(").append(i).append(").build();\n");
        }

        private void generics(TypeMirror t) {
            if (t.getKind() == TypeKind.ARRAY) {
                out.append("            .element(").append(erased(((ArrayType) t).getComponentType()))
                    .append(".class)\n");
                return;
            }
            if (t.getKind() != TypeKind.DECLARED) {
                return;
            }
            List<? extends TypeMirror> args = ((DeclaredType) t).getTypeArguments();
            TypeMirror raw = types.erasure(t);
            if (args.size() == 2 && types.isAssignable(raw, types.erasure(declared("java.util.Map")))) {
                out.append("            .key(").append(rawOf(args.get(0))).append(".class)\n");
                out.append("            .element(").append(rawOf(args.get(1))).append(".class)\n");
            } else if (args.size() == 1 && types.isAssignable(raw, types.erasure(declared("java.util.Collection")))) {
                out.append("            .element(").append(rawOf(args.get(0))).append(".class)\n");
            }
        }

        private String rawOf(TypeMirror t) {
            return t.getKind() == TypeKind.DECLARED || t.getKind() == TypeKind.ARRAY ? types.erasure(t).toString()
                : "java.lang.Object";
        }

        private TypeMirror declared(String name) {
            return elements.getTypeElement(name).asType();
        }

        private void model(List<Prop> props) {
            out.append("\n    /** The model of {@link ").append(typeName).append("}. */\n");
            out.append("    public static final ").append(PROPS).append("PropertyModel<").append(typeName)
                .append("> MODEL = ").append(PROPS).append("PropertyModel.builder(").append(typeName)
                .append(".class).source(\"generated\")\n");
            String annotations = annotations(List.of(type));
            if (!annotations.isEmpty()) {
                out.append("        .annotations(").append(annotations).append(")\n");
            }
            for (Prop p : props) {
                out.append("        .property(").append(constant(p.name())).append(")\n");
            }
            String factory = factory();
            if (factory != null) {
                out.append("        .factory(").append(factory).append(")\n");
            }
            for (TypeElement s : subtypes(type)) {
                out.append("        .subtype(").append(s.getQualifiedName()).append(".class)\n");
            }
            out.append("        .build();\n");
        }

        private String factory() {
            if (type.getModifiers().contains(Modifier.ABSTRACT) || type.getKind() == ElementKind.INTERFACE) {
                return null;
            }
            for (VariableElement f : ElementFilter.fieldsIn(type.getEnclosedElements())) {
                if (f.getSimpleName().contentEquals("DEFAULTS") && f.getModifiers().contains(Modifier.STATIC)
                    && f.getModifiers().contains(Modifier.PUBLIC) && types.isAssignable(f.asType(), type.asType())) {
                    return "() -> " + typeName + ".DEFAULTS";
                }
            }
            for (ExecutableElement c : ElementFilter.constructorsIn(type.getEnclosedElements())) {
                if (c.getParameters().isEmpty() && visible(c)) {
                    return typeName + "::new";
                }
            }
            return "() -> (" + typeName + ") " + PROPS + "Defaults.of(" + typeName + ".class)";
        }

        private List<TypeElement> subtypes(TypeElement t) {
            List<TypeElement> list = new ArrayList<>();
            if (!t.getModifiers().contains(Modifier.SEALED)) {
                return list;
            }
            for (TypeMirror m : t.getPermittedSubclasses()) {
                TypeElement s = (TypeElement) ((DeclaredType) m).asElement();
                list.addAll(subtypes(s));
                if (s.getKind() != ElementKind.INTERFACE && !s.getModifiers().contains(Modifier.ABSTRACT)) {
                    list.add(s);
                }
            }
            return list;
        }

        private void accessors(List<Prop> props, int i) {
            Prop p = props.get(i);
            String vt = p.type().toString();
            String cap = Character.toUpperCase(p.name().charAt(0)) + p.name().substring(1);
            out.append("\n    /** The {@code ").append(p.name()).append("} of {@code o}. */\n");
            out.append("    public static ").append(vt).append(' ').append(p.name()).append('(').append(typeName)
                .append(" o) {\n        return ").append(p.get()).append(";\n    }\n");
            if (p.component()) {
                out.append("\n    /** A copy of {@code o} with {@code ").append(p.name()).append("} {@code v}. */\n");
                out.append("    public static ").append(typeName).append(" with").append(cap).append('(')
                    .append(typeName).append(" o, ").append(vt).append(" v) {\n        return ")
                    .append(wither(props, i, false)).append(";\n    }\n");
            }
        }

        // ------------------------------------------------------------ annotations

        private String annotations(List<? extends Element> annotated) {
            StringBuilder b = new StringBuilder();
            Set<String> seen = new LinkedHashSet<>();
            for (Element e : annotated) {
                for (AnnotationMirror m : e.getAnnotationMirrors()) {
                    TypeElement at = (TypeElement) m.getAnnotationType().asElement();
                    if (!runtime(at) || !seen.add(at.getQualifiedName().toString())) {
                        continue;
                    }
                    if (!b.isEmpty()) {
                        b.append(",\n                ");
                    }
                    b.append(annotation(m));
                }
            }
            return b.toString();
        }

        private boolean runtime(TypeElement annotationType) {
            Retention r = annotationType.getAnnotation(Retention.class);
            return r != null && r.value() == RetentionPolicy.RUNTIME;
        }

        private String annotation(AnnotationMirror m) {
            StringBuilder b = new StringBuilder(PROPS).append("Hints.annotation(")
                .append(types.erasure(m.getAnnotationType())).append(".class");
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e
                : m.getElementValues().entrySet()) {
                b.append(", \"").append(e.getKey().getSimpleName()).append("\", ")
                    .append(value(e.getValue(), e.getKey().getReturnType()));
            }
            return b.append(')').toString();
        }

        private String value(AnnotationValue v, TypeMirror declared) {
            return v.accept(new SimpleAnnotationValueVisitor14<String, Void>() {
                @Override
                public String visitBoolean(boolean b, Void p) {
                    return Boolean.toString(b);
                }

                @Override
                public String visitByte(byte b, Void p) {
                    return "(byte) " + b;
                }

                @Override
                public String visitChar(char c, Void p) {
                    return "'" + escape(String.valueOf(c), '\'') + "'";
                }

                @Override
                public String visitDouble(double d, Void p) {
                    return Double.isNaN(d) ? "Double.NaN" : Double.isInfinite(d) ? (d > 0 ? "Double.POSITIVE_INFINITY"
                        : "Double.NEGATIVE_INFINITY") : d + "d";
                }

                @Override
                public String visitFloat(float f, Void p) {
                    return Float.isNaN(f) ? "Float.NaN" : Float.isInfinite(f) ? (f > 0 ? "Float.POSITIVE_INFINITY"
                        : "Float.NEGATIVE_INFINITY") : f + "f";
                }

                @Override
                public String visitInt(int i, Void p) {
                    return Integer.toString(i);
                }

                @Override
                public String visitLong(long i, Void p) {
                    return i + "L";
                }

                @Override
                public String visitShort(short s, Void p) {
                    return "(short) " + s;
                }

                @Override
                public String visitString(String s, Void p) {
                    return "\"" + escape(s, '"') + "\"";
                }

                @Override
                public String visitType(TypeMirror t, Void p) {
                    return types.erasure(t) + ".class";
                }

                @Override
                public String visitEnumConstant(VariableElement c, Void p) {
                    return ((TypeElement) c.getEnclosingElement()).getQualifiedName() + "." + c.getSimpleName();
                }

                @Override
                public String visitAnnotation(AnnotationMirror a, Void p) {
                    return annotation(a);
                }

                @Override
                public String visitArray(List<? extends AnnotationValue> values, Void p) {
                    TypeMirror component = ((ArrayType) declared).getComponentType();
                    StringBuilder b = new StringBuilder("new ").append(types.erasure(component)).append("[] {");
                    for (int i = 0; i < values.size(); i++) {
                        b.append(i == 0 ? "" : ", ").append(value(values.get(i), component));
                    }
                    return b.append('}').toString();
                }
            }, null);
        }
    }

    static String escape(String s, char quote) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c == quote) {
                        b.append('\\');
                    }
                    if (c < 0x20 || c > 0x7e) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
