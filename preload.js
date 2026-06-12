const { contextBridge, ipcRenderer } = require('electron');

// Tiny, boring bridge. The UI can ask for the engine source and the app
// version. That's the whole surface.
contextBridge.exposeInMainWorld('zivkord', {
  getCoreSource: () => ipcRenderer.invoke('core-source'),
  getVersion: () => ipcRenderer.invoke('app-version'),
});
