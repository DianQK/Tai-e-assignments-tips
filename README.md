<div align="center">
  <a href="https://tai-e.pascal-lab.net/">
    <img src="https://tai-e.pascal-lab.net/o-tai-e.webp" height="200">
  </a>

## Tai-e Assignments for Static Program Analysis
</div>

### Getting Started

If you want to do the assignments, please start with "*Overview of Tai-e Assignments*" [[中文](https://tai-e.pascal-lab.net/intro/overview.html)][[English](https://tai-e.pascal-lab.net/en/intro/overview.html)].

## Tips

每一个作业都可以有帮助的方案：

多调试，尝试看看各种实例在运行时到底是个啥，对好奇的类要点进去看看都有啥方法。

由于项目使用了接口/抽象类/继承等多态的能力，代码上看到的类可能还需要/只需要处理对应的子类，可以直接使用 `getClass()` 查看并转换成子类使用。

> 为了项目的复用，`Solver`/Data Flow Framework 使用了大量接口抽象，不应对这些抽象进行子类转换。比如在 `Solver` 中，不应当将 `DataflowAnalysis`、`Node`、`Fact` 等转换成具体的类型使用。

作业相关在各自的 README.md 中。
