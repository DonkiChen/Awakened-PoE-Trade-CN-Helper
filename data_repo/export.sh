(cd ../poe-dat-viewer/lib/ && npm install && npx tsc) || { echo "编译 poe-dat-viewer 失败, 请确认你在 data_repo 目录下"; exit; }

(cd exported/intl && node ../../../poe-dat-viewer/lib/dist/cli/run.js) || echo "导出国际服数据失败"
(cd exported/tencent && node ../../../poe-dat-viewer/lib/dist/cli/run.js) || echo "导出国服数据失败"

echo "大功告成"