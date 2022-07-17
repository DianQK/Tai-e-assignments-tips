# A5

## 小心检查上下文

代码上要好好思考上下文的实例使用是否和制定的处理规则一致，可能有一些绕。

## 认真阅读

pascal.taie.analysis.pta.ci.PointerFlowGraph

这个类表示程序的指针流图。它还维护着从变量、静态字段、实例字段、数组索引到相应指针（即 PFG 节点）的映射，因此你可以利用这个类的 API 获得各种指针。

> 从这里获取 `VarPtr` 之类的。

## 仔细回顾 Load/Store

Load/Store 的含义到底是什么？`var.getStoreFields()` 获取的结果与 `var` 的关系是什么？

## Java 的嵌套类

嵌套的类可以使用外面类的属性。

## `getStoreFields` 不包含静态字段

思考一下该在什么地方处理？

## 方法调用可能没有返回值

需要处理这个场景。

## 正确判断 `processCall` 中调用边的过滤

使用 `callGraph.contains(method)` 真的对吗？
