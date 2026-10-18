# tools

## gen-completions.cjs

从 TypeScript 自带的标准库声明（`lib.*.d.ts`）生成编辑器的补全表 `app/src/main/assets/cm6/completions.js`。
补全数据因此来自 TypeScript 本身，不是手写名单。

运行前需要临时装一份 TypeScript（仅构建用，不进 App）：

```powershell
mkdir tmp\tsgen
cd tmp\tsgen
npm i typescript@5.6.3            # 或 pnpm add typescript@5.6.3

# 回到仓库根的 doumcp_src
$env:SPARK_TS_LIB    = "..\tmp\tsgen\node_modules\typescript\lib"
$env:SPARK_TS_MODULE = "..\tmp\tsgen\node_modules\typescript"
node tools/gen-completions.cjs
```

产出：`app/src/main/assets/cm6/completions.js`（50 KB 左右），编辑器启动时直接加载。

要增加补全范围（比如加 `Intl`、`url`）：在 `MEMBER_TYPES` / `STATIC_SOURCES` / `files` 里加上对应声明即可。
