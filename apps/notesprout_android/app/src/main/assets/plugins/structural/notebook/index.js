// NoteSprout Plugin — Notebook
// Plugin ID: com.notesprout.structural.notebook
// Version: 1

(function () {

  function getManifest() {
    return JSON.stringify({
      pluginId: "com.notesprout.structural.notebook",
      version: 1,
      name: "Notebook"
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

  // Expose via namespace so callFunction can reach these without global name collisions.
  globalThis["com.notesprout.structural.notebook"] = {
    getManifest: getManifest,
    onCreate: onCreate,
    onLoad: onLoad,
    onSave: onSave
  };

})();
