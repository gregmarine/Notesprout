(function() {
  const PLUGIN_ID = "com.notesprout.structural.notebook";
  const VERSION = 1;

  function getManifest() {
    return JSON.stringify({
      pluginId: PLUGIN_ID,
      version: VERSION,
      name: "Notebook",
      type: "structural"
    });
  }

  function createObject(parentId, dataJson) {
    const data = JSON.parse(dataJson);
    if (!data.name) throw new Error("Notebook requires a name");
    const id = context.newId();
    return JSON.stringify({
      id: id,
      parentId: id,
      pluginId: PLUGIN_ID,
      boundingBox: JSON.stringify({x: 0.0, y: 0.0, width: 0.0, height: 0.0}),
      order: 0,
      createdAt: context.now(),
      updatedAt: context.now(),
      deletedAt: null,
      syncVersion: 0,
      data: JSON.stringify({
        pluginVersion: VERSION,
        name: data.name,
        cover: data.cover || "#000000",
        createdWith: "com.notesprout.android",
        appVersion: context.getAppVersion()
      })
    });
  }

  function validate(objectJson) {
    try {
      const obj = JSON.parse(objectJson);
      const data = JSON.parse(obj.data);
      return !!(data.pluginVersion && data.name && data.createdWith);
    } catch(e) {
      return false;
    }
  }

  function onLoad(objectJson) {
    return objectJson;
  }

  function onSave(objectJson) {
    return objectJson;
  }

  __plugins__[PLUGIN_ID] = {
    getManifest,
    createObject,
    validate,
    onLoad,
    onSave
  };

  context.registerPlugin(PLUGIN_ID);
})();
