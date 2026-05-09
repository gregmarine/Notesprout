// NoteSprout Plugin — Page
// Plugin ID: com.notesprout.structural.page
// Version: 1

(function () {

  function getManifest() {
    return JSON.stringify({
      pluginId: "com.notesprout.structural.page",
      version: 1,
      name: "Page"
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

  globalThis["com.notesprout.structural.page"] = {
    getManifest: getManifest,
    onCreate: onCreate,
    onLoad: onLoad,
    onSave: onSave
  };

})();
