(function() {
  const PLUGIN_ID = "com.notesprout.structural.layer";
  const VERSION = 1;

  function getManifest() {
    return JSON.stringify({
      pluginId: PLUGIN_ID,
      version: VERSION,
      name: "Layer",
      type: "structural"
    });
  }

  function createObject(parentId, dataJson) {
    const data = JSON.parse(dataJson);
    const order = data.order || 1;
    const name = data.name || "Layer-" + order;
    return JSON.stringify({
      id: context.newId(),
      parentId: parentId,
      pluginId: PLUGIN_ID,
      boundingBox: JSON.stringify({x: 0.0, y: 0.0, width: 0.0, height: 0.0}),
      order: order,
      createdAt: context.now(),
      updatedAt: context.now(),
      deletedAt: null,
      syncVersion: 0,
      data: JSON.stringify({
        pluginVersion: VERSION,
        name: name,
        isLocked: false,
        isVisible: true,
        opacity: 1.0
      })
    });
  }

  function validate(objectJson) {
    try {
      const obj = JSON.parse(objectJson);
      const data = JSON.parse(obj.data);
      return !!(
        data.pluginVersion &&
        data.name &&
        typeof data.isLocked === 'boolean' &&
        typeof data.isVisible === 'boolean' &&
        data.opacity >= 0.0 &&
        data.opacity <= 1.0
      );
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
