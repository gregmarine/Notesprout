// NoteSprout Plugin — Eraser
// Plugin ID: com.notesprout.tools.eraser
// Version: 1

(function () {

  function getManifest() {
    return JSON.stringify({
      pluginId: "com.notesprout.tools.eraser",
      version: 1,
      name: "Eraser"
    });
  }

  function onCreate(parentId, data) {
    // stub
  }

  function onLoad(objectJson) {
    // stub
  }

  function onSave(objectJson) {
    return objectJson;
  }

  globalThis["com.notesprout.tools.eraser"] = {
    getManifest: getManifest,
    onCreate: onCreate,
    onLoad: onLoad,
    onSave: onSave
  };

})();
