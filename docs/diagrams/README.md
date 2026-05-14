# Diagrams

All diagrams are [Mermaid](https://mermaid.js.org/) `.mmd` files – text-based,
versionable, rendered natively by GitHub.

| Diagram | File | Type |
|---|---|---|
| Async Log Flow | [`async-log-flow.mmd`](async-log-flow.mmd) | Sequence |
| Class Overview | [`class-overview.mmd`](class-overview.mmd) | Class |
| Deployment Variants | [`deployment-variants.mmd`](deployment-variants.mmd) | Flowchart |
| Backpressure Logic | [`backpressure-flow.mmd`](backpressure-flow.mmd) | State |
| Quickstart | [`quickstart-flow.mmd`](quickstart-flow.mmd) | Flowchart |

## Render

```bash
# Locally via mermaid-cli
npx @mermaid-js/mermaid-cli -i async-log-flow.mmd -o async-log-flow.png
```
