// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: stands in for EA's LayersList dialog. The
// compiled EA files only ask whether a layer is hidden (WHeightMapEdit's select-similar). Layers
// are not in the first native release, so no layer is hidden. When the Layers panel arrives it
// keeps the hidden set here; the layer NAME of each object stays in its properties (TheKey_objectLayer)
// and is written back by EA's code unchanged either way.
#pragma once

#include "Common/AsciiString.h"

class MapObject;
class PolygonTrigger;

class LayersList
{
public:
	// The layer of an object is its TheKey_objectLayer property, which EA's code keeps; the list itself
	// (the Layers panel) is not in the native editor yet, so adding and removing only answer as EA does.
	void addMapObjectToLayersList(MapObject *obj, AsciiString layerName = AsciiString::TheEmptyString) { (void)obj; (void)layerName; }
	AsciiString removeMapObjectFromLayersList(MapObject *obj) { (void)obj; return AsciiString::TheEmptyString; }
	void addPolygonTriggerToLayersList(PolygonTrigger *trig, AsciiString layerName = AsciiString::TheEmptyString) { (void)trig; (void)layerName; }
	AsciiString removePolygonTriggerFromLayersList(PolygonTrigger *trig) { (void)trig; return AsciiString::TheEmptyString; }
	void enableUpdates() {}
	void disableUpdates() {}
	void resetLayers() {}
	Bool isLayerHidden(const AsciiString &layerName) const
	{
		(void)layerName;
		return false;
	}
};

extern LayersList *TheLayersList;
