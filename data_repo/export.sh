
(cd ../poe-dat-viewer/lib/ && npm install && npx tsc) || { echo "编译 poe-dat-viewer 失败, 请确认你在 data_repo 目录下"; exit; }

(cd exported/intl_amsco2 && node ../../../poe-dat-viewer/lib/dist/cli/run.js) || echo "导出国际服 amsco2 补丁数据失败"
#(cd exported/intl_poedb && node ../../../poe-dat-viewer/lib/dist/cli/run.js) || echo "导出国际服 poedb 补丁数据失败"
#(cd exported/tencent && node ../../../poe-dat-viewer/lib/dist/cli/run.js) || echo "导出国服数据失败"

echo "大功告成"