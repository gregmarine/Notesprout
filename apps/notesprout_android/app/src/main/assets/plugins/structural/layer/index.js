// NoteSprout Plugin — Layer
// Plugin ID: com.notesprout.structural.layer
// Version: 1

(function () {

  function getManifest() {
    return JSON.stringify({
      pluginId: "com.notesprout.structural.layer",
      version: 1,
      name: "Layer"
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

  globalThis["com.notesprout.structural.layer"] = {
    getManifest: getManifest,
    onCreate: onCreate,
    onLoad: onLoad,
    onSave: onSave
  };

})();
