// שרת מקומי קטן לטלפרומפטר: מגיש את index.html ב-localhost (כדי שהרשאת המיקרופון תישמר),
// פותח חלון אפליקציה בכרום/אדג', ונסגר לבד כשהחלון נסגר.
const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const PORT = 5757;
const URL = `http://localhost:${PORT}/`;
const IDLE_EXIT_MS = 45000;

const BROWSERS = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  path.join(process.env.LOCALAPPDATA || '', 'Google\\Chrome\\Application\\chrome.exe'),
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
];

function openWindow() {
  const exe = BROWSERS.find(p => p && fs.existsSync(p));
  if (!exe) {
    spawn('cmd', ['/c', 'start', '', URL], { detached: true, stdio: 'ignore' }).unref();
    return;
  }
  spawn(exe, [`--app=${URL}`, '--window-size=1280,800'], { detached: true, stdio: 'ignore' }).unref();
}

let lastPing = Date.now();

const server = http.createServer((req, res) => {
  if (req.url.startsWith('/ping')) {
    lastPing = Date.now();
    res.writeHead(204);
    return res.end();
  }
  fs.readFile(path.join(__dirname, 'index.html'), (err, data) => {
    if (err) { res.writeHead(500); return res.end('index.html not found'); }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(data);
  });
});

server.on('error', err => {
  // השרת כבר רץ מפתיחה קודמת - רק פותחים חלון נוסף
  if (err.code === 'EADDRINUSE') { openWindow(); process.exit(0); }
  throw err;
});

server.listen(PORT, '127.0.0.1', () => {
  openWindow();
  setInterval(() => {
    if (Date.now() - lastPing > IDLE_EXIT_MS) process.exit(0);
  }, 5000);
});
