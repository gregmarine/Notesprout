(function() {
  const PLUGIN_ID = "com.notesprout.tools.gel_pen";
  const VERSION = 1;

  function getManifest() {
    return JSON.stringify({
      pluginId: PLUGIN_ID,
      version: VERSION,
      name: "Gel Pen",
      type: "tool"
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

  globalThis[PLUGIN_ID] = {
    getManifest,
    onCreate,
    onLoad,
    onSave
  };

  context.registerPlugin(PLUGIN_ID);
})();
