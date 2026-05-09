(function() {
  const PLUGIN_ID = "com.notesprout.structural.page";
  const VERSION = 1;

  function getManifest() {
    return JSON.stringify({
      pluginId: PLUGIN_ID,
      version: VERSION,
      name: "Page",
      type: "structural"
    });
  }

  function createObject(parentId, dataJson) {
    const data = JSON.parse(dataJson);
    if (!data.width || !data.height) throw new Error("Page requires width and height");
    return JSON.stringify({
      id: context.newId(),
      parentId: parentId,
      pluginId: PLUGIN_ID,
      boundingBox: JSON.stringify({
        x: 0.0,
        y: 0.0,
        width: data.width,
        height: data.height
      }),
      order: data.order || 1,
      createdAt: context.now(),
      updatedAt: context.now(),
      deletedAt: null,
      syncVersion: 0,
      data: JSON.stringify({
        pluginVersion: VERSION,
        width: data.width,
        height: data.height,
        template: data.template || null
      })
    });
  }

  function validate(objectJson) {
    try {
      const obj = JSON.parse(objectJson);
      const data = JSON.parse(obj.data);
      return !!(data.pluginVersion && data.width > 0 && data.height > 0);
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

  globalThis[PLUGIN_ID] = {
    getManifest,
    createObject,
    validate,
    onLoad,
    onSave
  };

  context.registerPlugin(PLUGIN_ID);
})();
