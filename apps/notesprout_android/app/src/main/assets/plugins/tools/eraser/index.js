(function() {
  const PLUGIN_ID = "com.notesprout.tools.eraser";
  const VERSION = 1;

  function getManifest() {
    return JSON.stringify({
      pluginId: PLUGIN_ID,
      version: VERSION,
      name: "Eraser",
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

  __plugins__[PLUGIN_ID] = {
    getManifest,
    onCreate,
    onLoad,
    onSave
  };

  context.registerPlugin(PLUGIN_ID);
})();
