/*
 * Copyright 2023 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.spring;

import lombok.Value;
import lombok.With;
import org.openrewrite.*;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Marker;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.openrewrite.Tree.randomId;

/**
 * This visitor can remove the specified method calls if it can be deleted without compile error,
 * It can be used to remove deprecated or unnecessary method calls, but be sure to carefully
 * review your code before deleting any methods to avoid errors or unexpected behavior.
 */
public class RemoveMethodInvocationsVisitor extends JavaVisitor<ExecutionContext> {
    private final Map<MethodMatcher, Predicate<List<Expression>>> matchers;

    public RemoveMethodInvocationsVisitor(Map<MethodMatcher, Predicate<List<Expression>>> matchers) {
        this.matchers = matchers;
    }

    public RemoveMethodInvocationsVisitor(List<String> methodSignatures) {
        matchers = methodSignatures.stream().collect(Collectors.toMap(
            MethodMatcher::new,
            signature -> args -> true
        ));
    }

    @Override
    public J visitJavaSourceFile(JavaSourceFile cu, ExecutionContext ctx) {
        return cu instanceof J.CompilationUnit ? visitCompilationUnit((J.CompilationUnit) cu, ctx) : cu;
    }

    @Override
    public J visitMethodInvocation(J.MethodInvocation method,
                                   ExecutionContext ctx) {
        if (inMethodCallChain()) {
            List<Expression> newArgs = ListUtils.map(method.getArguments(), arg -> (Expression) this.visit(arg, ctx));
            return method.withArguments(newArgs);
        }

        method = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
        J j = removeMethods(method, 0, isLambdaBody(), new Stack<>());
        if (j != null) {
            j = j.withPrefix(method.getPrefix());
        }

        if (method.getArguments().stream().allMatch(ToBeRemoved::hasMarker)) {
            return ToBeRemoved.withMarker(j);
        }
        return j;
    }

    @Nullable
    private J removeMethods(Expression expression, int depth, boolean isLambdaBody, Stack<Space> selectAfter) {
        if (!(expression instanceof J.MethodInvocation)) {
            return expression;
        }

        boolean isStatement = isStatement();
        J.MethodInvocation m = (J.MethodInvocation) expression;

        if (matchers.entrySet().stream().anyMatch(entry -> matches(m, entry.getKey(), entry.getValue()))) {
            boolean hasSameReturnType = TypeUtils.isAssignableTo(m.getMethodType().getReturnType(), m.getSelect().getType());
            boolean removable = (isStatement && depth == 0) || hasSameReturnType;
            if (!removable) {
                return expression;
            }

            if (m.getSelect() instanceof J.Identifier || m.getSelect() instanceof J.NewClass) {
                boolean keepSelect = depth != 0;
                if (keepSelect) {
                    selectAfter.add(getSelectAfter(m));
                    return m.getSelect();
                } else {
                    if (isStatement) {
                        return null;
                    } else if (isLambdaBody) {
                        return ToBeRemoved.withMarker(J.Block.createEmptyBlock());
                    } else {
                        return hasSameReturnType ? m.getSelect() : expression;
                    }
                }
            } else if (m.getSelect() instanceof J.MethodInvocation) {
                return removeMethods(m.getSelect(), depth, isLambdaBody, selectAfter);
            }
        }

        J.MethodInvocation method = m.withSelect((Expression) removeMethods(m.getSelect(), depth + 1, isLambdaBody, selectAfter));

        // inherit prefix
        if (!selectAfter.isEmpty()) {
            method = inheritSelectAfter(method, selectAfter);
        }

        return method;
    }

    private boolean matches(J.MethodInvocation m, MethodMatcher matcher, Predicate<List<Expression>> argsMatches) {
        return matcher.matches(m) && argsMatches.test(m.getArguments());
    }

    private boolean isStatement() {
        return getCursor().dropParentUntil(p -> p instanceof J.Block ||
                                                p instanceof J.Assignment ||
                                                p instanceof J.VariableDeclarations.NamedVariable ||
                                                p instanceof J.Return ||
                                                p instanceof JContainer ||
                                                p == Cursor.ROOT_VALUE
        ).getValue() instanceof J.Block;
    }

    private boolean isLambdaBody() {
        Object parent = getCursor().getParent().getValue();
        return parent instanceof J.Lambda && ((J.Lambda) parent).getBody() == getCursor().getValue();
    }

    private boolean inMethodCallChain() {
        return getCursor().dropParentUntil(p -> !(p instanceof JRightPadded)).getValue() instanceof J.MethodInvocation;
    }

    private J.MethodInvocation inheritSelectAfter(J.MethodInvocation method, Stack<Space> prefix) {
        return (J.MethodInvocation) new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public <T> JRightPadded<T> visitRightPadded(@Nullable JRightPadded<T> right,
                                                        JRightPadded.Location loc,
                                                        ExecutionContext executionContext) {
                return prefix.isEmpty() ? right : right.withAfter(prefix.pop());
            }
        }.visit(method, new InMemoryExecutionContext());
    }

    private Space getSelectAfter(J.MethodInvocation method) {
        return new JavaIsoVisitor<List<Space>>() {
            @Override
            public <T> JRightPadded<T> visitRightPadded(@Nullable JRightPadded<T> right,
                                                        JRightPadded.Location loc,
                                                        List<Space> selectAfter) {
                if (selectAfter.isEmpty()) {
                    selectAfter.add(right.getAfter());
                }
                return right;
            }
        }.reduce(method, new ArrayList<>()).get(0);
    }

    public static Predicate<List<Expression>> isTrueArgument() {
        return args -> (args != null &&
                        args.size() == 1 &&
                        isTrue(args.get(0))
        );
    }

    public static Predicate<List<Expression>> isFalseArgument() {
        return args -> (args != null &&
                        args.size() == 1 &&
                        isFalse(args.get(0))
        );
    }

    public static boolean isTrue(Expression expression) {
        return isBoolean(expression, Boolean.TRUE);
    }

    public static boolean isFalse(Expression expression) {
        return isBoolean(expression, Boolean.FALSE);
    }

    private static boolean isBoolean(Expression expression, Boolean b) {
        if (expression instanceof J.Literal) {
            return expression.getType() == JavaType.Primitive.Boolean && b.equals(((J.Literal) expression).getValue());
        }
        return false;
    }

    @Override
    public J.Lambda visitLambda(J.Lambda lambda, ExecutionContext ctx) {
        lambda = (J.Lambda) super.visitLambda(lambda, ctx);
        J body = lambda.getBody();
        if (body instanceof J.MethodInvocation && ToBeRemoved.hasMarker(body)) {
            Expression select = ((J.MethodInvocation) body).getSelect();
            List<J> parameters = lambda.getParameters().getParameters();
            if (select instanceof J.Identifier && !parameters.isEmpty() && parameters.get(0) instanceof J.VariableDeclarations) {
                J.VariableDeclarations declarations = (J.VariableDeclarations) parameters.get(0);
                if (((J.Identifier) select).getSimpleName().equals(declarations.getVariables().get(0).getSimpleName())) {
                    return ToBeRemoved.withMarker(lambda);
                }
            } else if (select instanceof J.MethodInvocation) {
                return lambda.withBody(select.withPrefix(body.getPrefix()));
            }
        } else if (body instanceof J.Block && ToBeRemoved.hasMarker(body)) {
            return ToBeRemoved.withMarker(lambda.withBody(ToBeRemoved.removeMarker(body)));
        }
        return lambda;
    }

    @Override
    public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
        int statementsCount = block.getStatements().size();

        block = (J.Block) super.visitBlock(block, ctx);
        List<Statement> statements = block.getStatements();
        if (!statements.isEmpty() && statements.stream().allMatch(ToBeRemoved::hasMarker)) {
            return ToBeRemoved.withMarker(block.withStatements(Collections.emptyList()));
        }

        if (statementsCount > 0 && statements.isEmpty()) {
            return ToBeRemoved.withMarker(block.withStatements(Collections.emptyList()));
        }

        if (statements.stream().anyMatch(ToBeRemoved::hasMarker)) {
            //noinspection DataFlowIssue
            return block.withStatements(statements.stream()
                .filter(s -> !ToBeRemoved.hasMarker(s) || s instanceof J.MethodInvocation && ((J.MethodInvocation) s).getSelect() instanceof J.MethodInvocation)
                .map(s -> s instanceof J.MethodInvocation && ToBeRemoved.hasMarker(s) ? ((J.MethodInvocation) s).getSelect().withPrefix(s.getPrefix()) : s)
                .collect(Collectors.toList()));
        }
        return block;
    }

    @Value
    @With
    private static class ToBeRemoved implements Marker {
        UUID id;
        static <J2 extends J> J2 withMarker(J2 j) {
            return j.withMarkers(j.getMarkers().addIfAbsent(new ToBeRemoved(randomId())));
        }
        static <J2 extends J> J2 removeMarker(J2 j) {
            return j.withMarkers(j.getMarkers().removeByType(ToBeRemoved.class));
        }
        static boolean hasMarker(J j) {
            return j.getMarkers().findFirst(ToBeRemoved.class).isPresent();
        }
    }
}
