// בבנייה למק בלי חשבון מפתח של אפל: חתימה "אד-הוק" - בלעדיה מק עם שבב M מסרב לפתוח את האפליקציה ("הקובץ פגום").
// עם חשבון מפתח (TP_ADHOC לא מוגדר) electron-builder חותם ושולח לאישור של אפל בעצמו.
const { execFileSync } = require('child_process');
const path = require('path');

exports.default = async function afterPack(ctx) {
  if (ctx.electronPlatformName !== 'darwin' || process.env.TP_ADHOC !== '1') return;
  // בבנייה אוניברסלית (שבב M + Intel) לא חותמים את שתי הגרסאות הזמניות - האיחוד דורש שיהיו זהות.
  // את האפליקציה המאוחדת חותמים בסוף בנפרד (בקובץ הבנייה ב-GitHub).
  if (ctx.appOutDir.includes('universal')) return;
  const appPath = path.join(ctx.appOutDir, `${ctx.packager.appInfo.productFilename}.app`);
  execFileSync('codesign', ['--force', '--deep', '--sign', '-', appPath], { stdio: 'inherit' });
};
