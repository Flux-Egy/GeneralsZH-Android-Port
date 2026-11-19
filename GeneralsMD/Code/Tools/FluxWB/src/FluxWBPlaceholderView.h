// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: EA's PlaceholderView, copied verbatim from
// WorldBuilder/src/wbview3d.cpp (lines 154-286), where it is local to that file. The shadow manager
// and the terrain code read TheTacticalView; the editor has no game view, so this stub answers.
#pragma once

#include "GameClient/View.h"

// The W3DShadowManager accesses TheTacticalView, so we have to create
// a stub class & object in Worldbuilder for it to access.
class PlaceholderView : public View
{
protected:
	Int m_width, m_height;																			///< Dimensions of the view
	Int m_originX, m_originY;																		///< Location of top/left view corner

protected:
	virtual View *prependViewToList( View *list ) override {return nullptr;};		///< Prepend this view to the given list, return the new list
	virtual View *getNextView() override { return nullptr; }				///< Return next view in the set
public:

	virtual void init() override {};

	virtual UnsignedInt getID() override { return 1; }

	virtual Drawable *pickDrawable( const ICoord2D *screen, Bool forceAttack, PickType pickType ) override {return nullptr;};			///< pick drawable given the screen pixel coords

	/// all drawables in the 2D screen region will call the 'callback'
	virtual Int iterateDrawablesInRegion( IRegion2D *screenRegion,
																				Bool (*callback)( Drawable *draw, void *userData ),
																				void *userData ) override {return 0;};
  virtual WorldToScreenReturn worldToScreenTriReturn( const Coord3D *w, ICoord2D *s ) override { return WTS_INVALID; };	///< Transform world coordinate "w" into screen coordinate "s"
	virtual Bool screenToTerrain( const ICoord2D *screen, Coord3D *world ) override { return false; }
	virtual PlaneClass::IntersectionResType screenToWorldAtZ( const ICoord2D *s, Coord3D *w, Real z ) override { return PlaneClass::NO_INTERSECTION; }
	virtual PlaneClass::IntersectionResType getScreenCornerWorldPointsAtZ( Coord3D *topLeft, Coord3D *topRight,
																							Coord3D *bottomRight, Coord3D *bottomLeft,
																							Real z, ViewportClass viewPort = ViewportClass() ) override { return PlaneClass::NO_INTERSECTION; }

	virtual void drawView() override {};															///< Render the world visible in this view.
	virtual void updateView() override {};															///< Render the world visible in this view.
	virtual void stepView() override {}; ///< Update view for every fixed time step

	virtual void setZoomLimited( Bool limit ) override {}			///< limit the zoom height
	virtual Bool isZoomLimited() const override { return TRUE; }							///< get status of zoom limit

	virtual void setWidth( Int width ) override { m_width = width; }
	virtual Int getWidth() override { return m_width; }
	virtual void setHeight( Int height ) override { m_height = height; }
	virtual Int getHeight() override { return m_height; }
	virtual void setOrigin( Int x, Int y) override { m_originX=x; m_originY=y;}				///< Sets location of top-left view corner on display
	virtual void getOrigin( Int *x, Int *y) override { *x=m_originX; *y=m_originY;}			///< Return location of top-left view corner on display

	virtual void forceRedraw() override { }

	virtual Bool isDoingScriptedCamera() override { return false; }
	virtual void stopDoingScriptedCamera() override {}

	virtual void lookAt( const Coord3D *o ) override {};											///< Center the view on the given coordinate
	virtual void initHeightForMap() override {};												///<  Init the camera height for the map at the current position.
	virtual void scrollBy( Coord2D *delta ){};														///< Shift the view by the given delta
	virtual void moveCameraTo(const Coord3D *o, Int frames, Int shutter,
														Bool orient, Real easeIn, Real easeOut) override {lookAt(o);};
	virtual void moveCameraAlongWaypointPath(Waypoint *way, Int frames, Int shutter,
														Bool orient, Real easeIn, Real easeOut) override {};
	virtual Bool isCameraMovementFinished() override {return true;};
	virtual void resetCamera(const Coord3D *location, Int frames, Real easeIn, Real easeOut) override {}; ///< Move camera to location, and reset to default angle & zoom.
	virtual void rotateCamera(Real rotations, Int frames, Real easeIn, Real easeOut) override {}; ///< Rotate camera about current viewpoint.
	virtual void rotateCameraTowardObject(ObjectID id, Int milliseconds, Int holdMilliseconds, Real easeIn, Real easeOut) override {};	///< Rotate camera to face an object, and hold on it
	virtual void cameraModFinalZoom(Real finalZoom, Real easeIn, Real easeOut) override {};			 ///< Final zoom for current camera movement.
	virtual void cameraModRollingAverage(Int framesToAverage) override {}; ///< Number of frames to average movement for current camera movement.
	virtual void cameraModFinalTimeMultiplier(Int finalMultiplier) override {}; ///< Final time multiplier for current camera movement.
	virtual void cameraModFinalPitch(Real finalPitch, Real easeIn, Real easeOut) override {};		 ///< Final pitch for current camera movement.
	virtual void cameraModFreezeTime() override {}					///< Freezes time during the next camera movement.
	virtual void cameraModFreezeAngle() override {}					///< Freezes time during the next camera movement.
	virtual void cameraModLookToward(Coord3D *pLoc) override {}			///< Sets a look at point during camera movement.
	virtual void cameraModFinalLookToward(Coord3D *pLoc) override {}			///< Sets a look at point during camera movement.
	virtual void cameraModFinalMoveTo(Coord3D *pLoc) override { };			///< Sets a final move to.
	virtual Bool isTimeFrozen() override { return false;}					///< Freezes time during the next camera movement.
	virtual Int	 getTimeMultiplier() override {return 1;};				///< Get the time multiplier.
	virtual void setTimeMultiplier(Int multiple) override {}; ///< Set the time multiplier.
	virtual void setDefaultView(Real pitch, Real angle, Real maxHeight) override {};
	virtual void zoomCamera( Real finalZoom, Int milliseconds, Real easeIn, Real easeOut ) override {};
	virtual void pitchCamera( Real finalPitch, Int milliseconds, Real easeIn, Real easeOut ) override {};

	virtual void setAngle( Real angle ) override {};																///< Rotate the view around the up axis to the given angle
	virtual Real getAngle() override { return 0; }
	virtual void setPitch( Real angle ) override {};																	///< Rotate the view around the horizontal axis to the given angle
	virtual Real getPitch() override { return 0; }							///< Return current camera pitch
	virtual void setAngleToDefault() override {}											///< Set the view angle back to default
	virtual void setPitchToDefault() override {}											///< Set the view pitch back to default
	virtual void getPosition(Coord3D *pos) {}											///< Return camera position

	virtual Real getHeightAboveGround() override { return 1; }
	virtual void setHeightAboveGround(Real z) override { }
	virtual Real getZoom() override { return 1; }
	virtual void setZoom(Real z) override { }
	virtual void zoomIn() {  }																			///< Zoom in, closer to the ground, limit to min
	virtual void zoomOut() {  }																		///< Zoom out, farther away from the ground, limit to max
	virtual void setZoomToDefault() override { }														///< Set zoom to default value
	virtual Real getMaxZoom() { return 0.0f; }
	virtual void setOkToAdjustHeight( Bool val ) override { }						///< Set this to adjust camera height

	virtual Real getTerrainHeightAtPivot() override { return 0.0f; }
	virtual Real getCurrentHeightAboveGround() override { return 0.0f; }

	virtual void getLocation ( ViewLocation *location ) override {};								///< write the view's current location in to the view location object
	virtual void setLocation ( const ViewLocation *location ) override {};								///< set the view's current location from to the view location object

	virtual ObjectID getCameraLock() const override { return INVALID_ID; }
	virtual void setCameraLock(ObjectID id) override {  }
	virtual void snapToCameraLock() override {  }
	virtual void setSnapMode( CameraLockType lockType, Real lockDist ) override {  }

	virtual Drawable *getCameraLockDrawable() const override { return nullptr; }
	virtual void setCameraLockDrawable(Drawable *drawable) override { }

	virtual void setMouseLock( Bool mouseLocked ) override {}					///< lock/unlock the mouse input to the tactical view
	virtual Bool isMouseLocked() override { return FALSE; }			///< is the mouse input locked to the tactical view?

	virtual void setFieldOfView( Real angle ) override {};							///< Set the horizontal field of view angle
	virtual Real getFieldOfView() override {return 0;};										///< Get the horizontal field of view angle

	virtual Bool setViewFilterMode(enum FilterModes) override {return FALSE;}	///<stub
	virtual void setViewFilterPos(const Coord3D *pos) override {};	///<stub
	virtual void setFadeParameters(Int fadeFrames, Int direction) override {};	///<stub
	virtual void set3DWireFrameMode(Bool enable) override { }; ///<stub
	virtual Bool setViewFilter(		enum FilterTypes m_viewFilterMode) override { return FALSE;}	///<stub
	virtual enum FilterModes	 getViewFilterMode() override {return (enum FilterModes)0;}			///< Turns on viewport special effect (black & white mode)
	virtual enum FilterTypes	 getViewFilterType() override {return (enum FilterTypes)0;}			///< Turns on viewport special effect (black & white mode)

	virtual void shake( const Coord3D *epicenter, CameraShakeType shakeType ) override {};

	virtual Real getFXPitch() const override { return 1.0f; }
	virtual void forceCameraAreaConstraintRecalc() override { }
	virtual void rotateCameraTowardPosition(const Coord3D *pLoc, Int milliseconds, Real easeIn, Real easeOut, Bool reverseRotation) override {};	///< Rotate camera to face an object, and hold on it

	virtual void setGuardBandBias( const Coord2D *gb ) override {};

};

