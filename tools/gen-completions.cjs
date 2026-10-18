/*
 * 从 TypeScript 自带的标准库声明里生成补全表。
 *
 * 目的：运行时不带 TypeScript（体积与启动代价太大），但补全数据仍由 TS 的 lib.*.d.ts 生成，
 * 而不是手写死名单。产物是一个赋值 `window.SPARK_TS_COMPLETIONS` 的 JS 文件，编辑器启动时加载。
 */
const fs = require('fs');
const path = require('path');
const ts = require(process.env.SPARK_TS_MODULE || 'typescript');

// 两个路径都可用环境变量覆盖：
//   SPARK_TS_LIB  —— typescript 包的 lib 目录（含 lib.es5.d.ts）
//   SPARK_OUT     —— 产出文件，默认 app/src/main/assets/cm6/completions.js
const libDir = process.env.SPARK_TS_LIB || path.join(__dirname, '..', 'node_modules', 'typescript', 'lib');
const outFile = process.env.SPARK_OUT
    || path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'cm6', 'completions.js');

// 需要提取成员的类型：都是脚本里真用得到的
const MEMBER_TYPES = [
    'String', 'Number', 'Boolean', 'Array', 'ReadonlyArray', 'Object', 'Date', 'RegExp',
    'Promise', 'Map', 'Set', 'WeakMap', 'WeakSet', 'Symbol', 'BigInt', 'Error',
];
// 需要提取静态成员的对象命名空间
// 静态成员写在 XxxConstructor 接口里，导出时合并回 Xxx
const STATIC_SOURCES = {
    JSON: 'JSON', Math: 'Math', Number: 'NumberConstructor', String: 'StringConstructor',
    Array: 'ArrayConstructor', Object: 'ObjectConstructor', Date: 'DateConstructor',
    RegExp: 'RegExpConstructor', Promise: 'PromiseConstructor',
};
const STATIC_TYPES = Object.keys(STATIC_SOURCES);

function libPath(name) {
    return path.join(libDir, 'lib.' + name + '.d.ts');
}

const files = [
    'es5', 'es2015.symbol', 'es2015.collection', 'es2015.core', 'es2015.iterable', 'es2015.promise',
    'es2015.generator', 'es2015.proxy', 'es2015.reflect', 'es2015.symbol.wellknown',
    'es2016.array.include', 'es2017.object', 'es2017.string', 'es2019.array', 'es2019.string',
    'es2020.string', 'es2020.promise', 'es2020.bigint', 'es2021.string', 'es2022.array',
].map(libPath).filter((file) => fs.existsSync(file));

const program = ts.createProgram(files, { noResolve: true, skipLibCheck: true });
const checker = program.getTypeChecker();

function docsOf(node) {
    const text = node.getSourceFile().getFullText();
    const ranges = ts.getLeadingCommentRanges(text, node.getFullStart()) || [];
    for (let index = ranges.length - 1; index >= 0; index--) {
        const piece = text.slice(ranges[index].pos, ranges[index].end);
        if (piece.startsWith('/**')) {
            return piece.replace(/^\/\*\*|\*\/$/g, '').replace(/^\s*\*\s?/gm, ' ').replace(/\s+/g, ' ').trim().slice(0, 160);
        }
    }
    return '';
}

function memberName(node) {
    if (!node.name) {
        return '';
    }
    if (ts.isIdentifier(node.name) || ts.isPrivateIdentifier(node.name)) {
        return node.name.text;
    }
    if (ts.isStringLiteral(node.name)) {
        return node.name.text;
    }
    return '';
}

function typeText(node) {
    if (node.type) {
        return node.type.getText().replace(/\s+/g, ' ').slice(0, 60);
    }
    if (ts.isMethodSignature(node)) {
        const params = node.parameters.map((param) => param.getText().replace(/\s+/g, ' ')).join(', ');
        return '(' + params + ')' + (node.type ? ': ' + node.type.getText() : '');
    }
    return '';
}

const members = {};
const statics = {};

for (const file of files) {
    const source = program.getSourceFile(file);
    if (!source) {
        continue;
    }
    for (const statement of source.statements) {
        if (!ts.isInterfaceDeclaration(statement)) {
            continue;
        }
        const name = statement.name.text;
        const staticName = Object.keys(STATIC_SOURCES).find((key) => STATIC_SOURCES[key] === name);
        const bucket = MEMBER_TYPES.includes(name) ? members : staticName ? statics : null;
        const bucketKey = staticName || name;
        if (!bucket) {
            continue;
        }
        const list = bucket[bucketKey] || (bucket[bucketKey] = []);
        for (const node of statement.members) {
            const label = memberName(node);
            if (!label || label.startsWith('__')) {
                continue;
            }
            if (list.some((item) => item.n === label)) {
                continue;
            }
            const isMethod = ts.isMethodSignature(node);
            list.push({ n: label, t: typeText(node), d: docsOf(node), m: isMethod ? 1 : 0 });
        }
    }
}

// 全局值（可在大括号里直接写的名字）
const globals = [];
const seen = new Set();
for (const file of files) {
    const source = program.getSourceFile(file);
    if (!source) {
        continue;
    }
    for (const statement of source.statements) {
        if (!ts.isVariableStatement(statement)) {
            continue;
        }
        for (const declaration of statement.declarationList.declarations) {
            if (!ts.isIdentifier(declaration.name) || !declaration.type) {
                continue;
            }
            const label = declaration.name.text;
            if (seen.has(label)) {
                continue;
            }
            seen.add(label);
            globals.push({ n: label, t: declaration.type.getText().replace(/\s+/g, ' ') });
        }
    }
}

const payload = { members, statics, globals };
const body = 'window.SPARK_TS_COMPLETIONS=' + JSON.stringify(payload) + ';';
fs.writeFileSync(outFile, body, 'utf8');

const sizeKb = (fs.statSync(outFile).size / 1024).toFixed(0);
console.log('types:', Object.keys(members).length, 'statics:', Object.keys(statics).length, 'globals:', globals.length, '->', outFile, sizeKb + 'KB');
