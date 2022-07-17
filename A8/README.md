# A8

## 污点分析传播的是污点

可以把污点传播当作和指针（对象）传播一样的方式考虑，所以 WorkList 的内容 ...

## 先考虑正确性

一次循环可以只做一点点事情，让 WorkList 慢慢迭代即可。
Source/Sink/Transfer 哪些放在 WorkList 的预处理，哪些在循环中？
