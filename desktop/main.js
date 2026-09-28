// גרסת דסקטופ: חלון צף מעל כל החלונות, מוסתר מהקלטות מסך.
// זיהוי דיבור: "מקוון" - Edge/Chrome בחלון מוסתר (עובד בכל מחשב), או Whisper מקומי (אם מותקן).
const { app, BrowserWindow, ipcMain, screen, globalShortcut } = require('electron');
const http = require('http');
const path = require('path');
const fs = require('fs');
const { spawn, execFile } = require('child_process');

// בהתקנה הקבצים יושבים ליד main.js; בפיתוח - בתיקייה שמעל
const APP_DIR = fs.existsSync(path.join(__dirname, 'index.html')) ? __dirname : path.join(__dirname, '..');
const IS_WIN = process.platform === 'win32', IS_MAC = process.platform === 'darwin';
const WHISPER_HOME = IS_WIN ? path.join(process.env.LOCALAPPDATA || '', 'VoiceTeleprompter')
  : path.join(app.getPath('home'), '.voice-teleprompter');
const PY = IS_WIN ? path.join(WHISPER_HOME, '.venv', 'Scripts', 'python.exe') : path.join(WHISPER_HOME, '.venv', 'bin', 'python');
const BOUNDS_FILE = () => path.join(app.getPath('userData'), 'float-bounds.json');

let win = null;
let mode = 'editor';

if (!app.requestSingleInstanceLock()) app.quit();
app.on('second-instance', () => { if (win) { win.show(); win.focus(); } });

// ---------------- Whisper (אופציונלי) ----------------
let py = null;
function startWhisper() {
  if (py || !fs.existsSync(PY)) return;
  const extra = process.env.TP_SIMULATE ? ['--simulate', process.env.TP_SIMULATE] : [];
  // בגרסה המותקנת הקובץ נפרס מחוץ ל-app.asar כדי ש-Python יוכל לקרוא אותו
  const script = path.join(APP_DIR, 'whisper_server.py').replace(`app.asar${path.sep}`, `app.asar.unpacked${path.sep}`);
  py = spawn(PY, [script, ...extra], {
    cwd: WHISPER_HOME, windowsHide: true, env: { ...process.env, PYTHONIOENCODING: 'utf-8' },
  });
  const log = fs.createWriteStream(path.join(WHISPER_HOME, 'whisper.log'), { flags: 'w' });
  py.stdout.pipe(log); py.stderr.pipe(log);
  py.on('exit', () => { py = null; });
}
function stopWhisper() { if (py) { try { py.kill(); } catch {} py = null; } }

// ---------------- גשר לזיהוי הדיבור של הדפדפן ----------------
function findBrowser() {
  const pf = process.env.ProgramFiles || 'C:\\Program Files', pf86 = process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)';
  const local = process.env.LOCALAPPDATA || '';
  // ב-Windows מעדיפים Edge: מותקן בכל מחשב, ובבדיקות הזיהוי שלו בעברית היה יציב יותר
  const list = IS_WIN ? [
    path.join(pf86, 'Microsoft\\Edge\\Application\\msedge.exe'),
    path.join(pf, 'Microsoft\\Edge\\Application\\msedge.exe'),
    path.join(pf, 'Google\\Chrome\\Application\\chrome.exe'),
    path.join(pf86, 'Google\\Chrome\\Application\\chrome.exe'),
    path.join(local, 'Google\\Chrome\\Application\\chrome.exe'),
  ] : IS_MAC ? [
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
  ] : ['/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/microsoft-edge'];
  const prefer = process.env.TP_BROWSER;   // לבדיקות: chrome / edge
  const found = list.filter(p => fs.existsSync(p));
  return (prefer && found.find(p => p.toLowerCase().includes(prefer))) || found[0] || null;
}

let bridgePort = 0, bridgeProc = null, sse = null, pending = null;
const toRenderer = msg => win && !win.isDestroyed() && win.webContents.send('engine', msg);

function startBridgeServer() {
  const srv = http.createServer((req, res) => {
    if (req.url === '/bridge') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      return res.end(fs.readFileSync(path.join(__dirname, 'bridge.html')));
    }
    if (req.url === '/bridge/events') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
      sse = res;
      req.on('close', () => { if (sse === res) sse = null; });
      if (pending) { res.write(`data: ${JSON.stringify(pending)}\n\n`); pending = null; }
      return;
    }
    if (req.method === 'POST' && req.url.startsWith('/bridge/')) {
      let body = '';
      req.on('data', c => { body += c; });
      req.on('end', () => {
        res.writeHead(204); res.end();
        let m; try { m = JSON.parse(body); } catch { return; }
        if (process.env.TP_AUTOTEST) console.log(`[bridge] ${req.url} ${body}`);
        if (req.url === '/bridge/text') toRenderer({ type: 'text', text: m.text });
        else if (req.url === '/bridge/status') toRenderer({ type: 'status', state: m.state, msg: m.msg });
      });
      return;
    }
    res.writeHead(404); res.end();
  });
  srv.listen(0, '127.0.0.1', () => { bridgePort = srv.address().port; launchBridge(); });
}

function launchBridge() {
  if (bridgeProc || !bridgePort) return;
  const exe = findBrowser();
  if (!exe) { toRenderer({ type: 'status', state: 'error', msg: 'לא נמצא Edge או Chrome במחשב' }); return; }
  bridgeProc = spawn(exe, [
    `--user-data-dir=${path.join(app.getPath('userData'), 'speech-bridge')}`,
    `--app=http://127.0.0.1:${bridgePort}/bridge`,
    '--window-position=-32000,-32000', '--window-size=320,200',
    '--use-fake-ui-for-media-stream',            // מאשר מיקרופון אוטומטית - רק בפרופיל הנפרד הזה
    '--no-first-run', '--no-default-browser-check', '--disable-extensions',
    '--disable-background-timer-throttling', '--disable-renderer-backgrounding', '--disable-backgrounding-occluded-windows',
  ], { stdio: 'ignore', detached: false });
  bridgeProc.on('exit', () => { bridgeProc = null; sse = null; });
}

function bridgeSend(msg) {
  if (!bridgeProc) launchBridge();
  if (sse) sse.write(`data: ${JSON.stringify(msg)}\n\n`);
  else pending = msg;   // יישלח ברגע שהחלון המוסתר יתחבר
}

function killBridge() {
  if (!bridgeProc) return;
  const pid = bridgeProc.pid;
  if (IS_WIN) execFile('taskkill', ['/pid', String(pid), '/T', '/F'], () => {});
  else try { bridgeProc.kill(); } catch {}
  bridgeProc = null;
}

// ---------------- מקש למצגת ----------------
function sendKey(key) {
  if (key !== 'right') return;
  if (IS_WIN) {
    execFile('powershell', ['-NoProfile', '-WindowStyle', 'Hidden', '-Command',
      "(New-Object -ComObject WScript.Shell).SendKeys('{RIGHT}')"], { windowsHide: true }, () => {});
  } else if (IS_MAC) {
    execFile('osascript', ['-e', 'tell application "System Events" to key code 124'], () => {});
  }
}

// ---------------- חלון ----------------
function defaultFloatBounds() {
  const wa = screen.getPrimaryDisplay().workArea;
  const width = Math.min(820, wa.width - 40), height = 240;
  return { x: Math.round(wa.x + (wa.width - width) / 2), y: wa.y + 6, width, height };
}

function loadFloatBounds() {
  try {
    const b = JSON.parse(fs.readFileSync(BOUNDS_FILE(), 'utf8'));
    const wa = screen.getDisplayMatching(b).workArea;   // לוודא שהחלון עדיין על מסך שקיים
    if (b.x + 80 > wa.x && b.x < wa.x + wa.width - 80 && b.y >= wa.y - 20 && b.y < wa.y + wa.height - 60) return b;
  } catch {}
  return defaultFloatBounds();
}

function saveFloatBounds() {
  if (mode !== 'float' || !win) return;
  try { fs.writeFileSync(BOUNDS_FILE(), JSON.stringify(win.getBounds())); } catch {}
}

function setMode(m, opts = {}) {
  mode = m;
  if (m === 'float') {
    win.setAlwaysOnTop(true, 'screen-saver');
    win.setMinimumSize(360, 110);
    win.setBounds(loadFloatBounds());
    // כשמעבירים שקפים - החלון לא לוקח פוקוס, כדי שהמצגת תקבל את המקשים
    win.setFocusable(!opts.noFocus);
  } else {
    win.setFocusable(true);
    win.setAlwaysOnTop(false);
    win.setOpacity(1);
    win.setMinimumSize(640, 480);
    const wa = screen.getDisplayMatching(win.getBounds()).workArea;
    const width = Math.min(1100, wa.width - 80), height = Math.min(800, wa.height - 80);
    win.setBounds({ x: Math.round(wa.x + (wa.width - width) / 2), y: Math.round(wa.y + (wa.height - height) / 2), width, height });
    win.focus();
  }
}

function createWindow() {
  win = new BrowserWindow({
    width: 1100, height: 800, minWidth: 640, minHeight: 480,
    frame: false, backgroundColor: '#0b0d10', show: false,
    title: 'טלפרומפטר · פרקטי AI', autoHideMenuBar: true,
    icon: path.join(__dirname, 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true },
  });
  // הלב של העניין: החלון לא יופיע בהקלטות מסך ובשיתופי מסך
  win.setContentProtection(true);
  win.loadFile(path.join(APP_DIR, 'index.html'));
  win.once('ready-to-show', () => win.show());
  win.on('moved', saveFloatBounds);
  win.on('resized', saveFloatBounds);
  win.on('closed', () => { win = null; });
  if (process.env.TP_AUTOTEST) runAutotest(process.env.TP_AUTOTEST);
}

// בדיקה אוטומטית (רק כשמוגדר TP_AUTOTEST=קובץ טקסט): ממלא טקסט, מתחיל, ומדפיס התקדמות
function runAutotest(textFile) {
  const text = fs.readFileSync(textFile, 'utf8');
  win.webContents.once('did-finish-load', async () => {
    const js = s => win.webContents.executeJavaScript(s);
    const engine = process.env.TP_ENGINE || 'browser';
    await js(`(() => { const e = document.getElementById('s-engine'); e.value = ${JSON.stringify(engine)}; e.dispatchEvent(new Event('change'));
      const s = document.getElementById('script'); s.value = ${JSON.stringify(text)}; s.dispatchEvent(new Event('input'));
      document.getElementById('go').click(); })()`);
    const t0 = Date.now();
    if (process.env.TP_TOGGLE) {   // השוואה: מבטלים את ההסתרה לכמה שניות
      setTimeout(() => { win.setContentProtection(false); console.log('[autotest] protection OFF'); }, 28000);
      setTimeout(() => { win.setContentProtection(true); console.log('[autotest] protection ON'); }, 32000);
    }
    const iv = setInterval(async () => {
      const r = await js(`({pos: window.__tp.pos, status: document.getElementById('status').textContent, eng: document.getElementById('engine-status').textContent})`);
      console.log(`[autotest] t=${((Date.now() - t0) / 1000).toFixed(1)}s pos=${r.pos} status=${r.status} engine=${r.eng}`);
    }, 1000);
    setTimeout(async () => {
      clearInterval(iv);
      const srt = await js('window.__tp.session && window.__tp.buildSrt(window.__tp.session, 0)');
      console.log('[autotest] SRT:\n' + srt);
      if (!process.env.TP_KEEP) app.quit();
    }, +(process.env.TP_SECONDS || 40) * 1000);
  });
}

ipcMain.on('info', e => { e.returnValue = { whisper: fs.existsSync(PY), browser: !!findBrowser(), platform: process.platform }; });
ipcMain.on('set-mode', (_e, m, opts) => win && setMode(m, opts || {}));
ipcMain.on('set-opacity', (_e, v) => win && mode === 'float' && win.setOpacity(Math.max(0.3, Math.min(1, v))));
ipcMain.on('minimize', () => win && win.minimize());
ipcMain.on('close', () => app.quit());
ipcMain.on('engine', (_e, msg) => bridgeSend(msg));
ipcMain.on('whisper', (_e, on) => (on ? startWhisper() : stopWhisper()));
ipcMain.on('send-key', (_e, key) => sendKey(key));

function registerHotkeys() {
  const send = a => win && win.webContents.send('hotkey', a);
  globalShortcut.register('Control+Alt+Space', () => send('toggle'));
  globalShortcut.register('Control+Alt+Down', () => send('next'));
  globalShortcut.register('Control+Alt+Up', () => send('prev'));
  globalShortcut.register('Control+Alt+M', () => send('mark'));
  globalShortcut.register('Control+Alt+H', () => {
    if (!win) return;
    win.isVisible() ? win.hide() : win.showInactive();
  });
}

app.whenReady().then(() => {
  startBridgeServer();
  createWindow();
  registerHotkeys();
});

app.on('will-quit', () => {
  globalShortcut.unregisterAll();
  stopWhisper();
  killBridge();
});
app.on('window-all-closed', () => app.quit());
