const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('desktop', {
  info: () => ipcRenderer.sendSync('info'),
  log: line => ipcRenderer.send('log', String(line)),
  setMode: (m, opts) => ipcRenderer.send('set-mode', m, opts),
  setOpacity: v => ipcRenderer.send('set-opacity', v),
  minimize: () => ipcRenderer.send('minimize'),
  close: () => ipcRenderer.send('close'),
  engine: msg => ipcRenderer.send('engine', msg),
  whisper: on => ipcRenderer.send('whisper', on),
  sendKey: key => ipcRenderer.send('send-key', key),
  onEngine: fn => ipcRenderer.on('engine', (_e, m) => fn(m)),
  onHotkey: fn => ipcRenderer.on('hotkey', (_e, a) => fn(a)),
  onHotkeys: fn => ipcRenderer.on('hotkeys', (_e, keys) => fn(keys)),
});
