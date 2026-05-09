// NoteSprout Plugin — Gel Pen
// Plugin ID: com.notesprout.tools.gel_pen
// Version: 1

(function () {

  function getManifest() {
    return JSON.stringify({
      pluginId: "com.notesprout.tools.gel_pen",
      version: 1,
      name: "Gel Pen"
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

  globalThis["com.notesprout.tools.gel_pen"] = {
    getManifest: getManifest,
    onCreate: onCreate,
    onLoad: onLoad,
    onSave: onSave
  };

})();
