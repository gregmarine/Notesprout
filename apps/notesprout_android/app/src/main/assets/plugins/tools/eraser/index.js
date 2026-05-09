(function() {
  var PLUGIN_ID = "com.notesprout.tools.eraser";
  var VERSION = 1;

  function getManifest() {
    return JSON.stringify({
      pluginId: PLUGIN_ID,
      version: VERSION,
      name: "Eraser",
      type: "tool"
    });
  }

  function rectsIntersect(a, b) {
    return !(
      a.x + a.width  < b.x ||
      b.x + b.width  < a.x ||
      a.y + a.height < b.y ||
      b.y + b.height < a.y
    );
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

    var radius = 12;
    var eraserBounds = {
      x: minX - radius,
      y: minY - radius,
      width: (maxX - minX) + radius * 2,
      height: (maxY - minY) + radius * 2
    };

    var layerId = context.getCurrentLayerId();
    var strokes = JSON.parse(data.query(layerId));

    var deletedAny = false;
    for (var j = 0; j < strokes.length; j++) {
      var bb = strokes[j].boundingBox;
      if (bb && rectsIntersect(eraserBounds, bb)) {
        data.softDelete(strokes[j].id);
        deletedAny = true;
      }
    }

    if (deletedAny) {
      var remaining = JSON.parse(data.query(layerId));
      var drawableStrokes = [];
      for (var k = 0; k < remaining.length; k++) {
        try {
          var d = JSON.parse(remaining[k].data);
          if (d.points && d.style) {
            drawableStrokes.push({ points: d.points, style: d.style });
          }
        } catch (e) {}
      }
      canvas.clear();
      canvas.redrawAll(JSON.stringify(drawableStrokes));
    }
  }

  function onCreate(parentId, objData) {}
  function onLoad(objectJson) {}
  function onSave(objectJson) { return objectJson; }

  __plugins__[PLUGIN_ID] = {
    getManifest: getManifest,
    onStrokeEnd: onStrokeEnd,
    onCreate: onCreate,
    onLoad: onLoad,
    onSave: onSave
  };

  context.registerPlugin(PLUGIN_ID);
})();
