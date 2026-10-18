// Generate the TypeScript standard-library bundle: only the reference closure
// that the language service actually needs (default: lib.es2020.d.ts).
//
// Usage:
//   $env:SPARK_TS_LIB = "...\typescript\lib"
//   node tools/gen-tslibs.cjs
const fs = require('fs');
const path = require('path');

const libDir = process.env.SPARK_TS_LIB || path.join(__dirname, '..', 'node_modules', 'typescript', 'lib');
const outFile = process.env.SPARK_OUT || path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'cm6', 'tslibs.js');
const root = process.env.SPARK_TS_ROOT || 'lib.es2020.d.ts';

const files = {};
const queue = [root];
while (queue.length) {
    const name = queue.shift();
    if (files[name]) {
        continue;
    }
    const full = path.join(libDir, name);
    if (!fs.existsSync(full)) {
        continue;
    }
    const text = fs.readFileSync(full, 'utf8');
    files[name] = text;
    // Follow `/// <reference lib="es2015" />` includes transitively.
    const pattern = /<reference\s+lib\s*=\s*"([^"]+)"\s*\/>/g;
    let match;
    while ((match = pattern.exec(text)) !== null) {
        queue.push('lib.' + match[1] + '.d.ts');
    }
}

fs.writeFileSync(outFile, 'window.TS_LIBS=' + JSON.stringify(files) + ';', 'utf8');
const size = (fs.statSync(outFile).size / 1024 / 1024).toFixed(2);
console.log('libs:', Object.keys(files).length, size + 'MB', '->', outFile);
