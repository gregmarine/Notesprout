(function() {
  var PLUGIN_ID = "com.notesprout.tools.gel_pen";
  var VERSION = 1;

  var style = {
    color: "#000000",
    baseWidth: 3,
    maxWidth: 6
  };

  function getManifest() {
    return JSON.stringify({
      pluginId: PLUGIN_ID,
      version: VERSION,
      name: "Gel Pen",
      type: "tool"
    });
  }

  function getStyle() {
    return JSON.stringify(style);
  }

  function onStrokeEnd(pointsJson) {
    var points = JSON.parse(pointsJson);
    if (!points || points.length === 0) return;

    var minX = points[0].x, maxX = points[0].x;
    var minY = points[0].y, maxY = points[0].y;
    for (var i = 1; i < points.length; i++) {
      if (points[i].x < minX) minX = points[i].x;
      if (points[i].x > maxX) maxX = points[i].x;
      if (points[i].y < minY) minY = points[i].y;
      if (points[i].y > maxY) maxY = points[i].y;
    }

    var obj = {
      id: context.newId(),
      parentId: context.getCurrentLayerId(),
      pluginId: PLUGIN_ID,
      boundingBox: {
        x: minX,
        y: minY,
        width: maxX - minX,
        height: maxY - minY
      },
      order: 0,
      createdAt: context.now(),
      updatedAt: context.now(),
      deletedAt: null,
      syncVersion: 0,
      data: JSON.stringify({ points: points, style: style })
    };
    data.save(JSON.stringify(obj));
  }

  function onCreate(parentId, objData) {}
  function onLoad(objectJson) {}
  function onSave(objectJson) { return objectJson; }

  __plugins__[PLUGIN_ID] = {
    getManifest: getManifest,
    getStyle: getStyle,
    onStrokeEnd: onStrokeEnd,
    onCreate: onCreate,
    onLoad: onLoad,
    onSave: onSave
  };

  context.registerPlugin(PLUGIN_ID);
})();
