/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

////////////////////////////////////////////////////////////////////////////////
//																																						//
//  (c) 2001-2003 Electronic Arts Inc.																				//
//																																						//
////////////////////////////////////////////////////////////////////////////////

///////////////////////////////////////////////////////////////////////////////////////
// FILE: FileTransfer.h
// Author: Matthew D. Campbell, December 2002
// Description: File Transfer wrapper using TheNetwork
///////////////////////////////////////////////////////////////////////////////////////

#pragma once

class GameInfo;

// Convenience functions
AsciiString GetBasePathFromPath( AsciiString path );
AsciiString GetFileFromPath( AsciiString path );

// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 Map-transfer path handling.
// ResolveMapTransferPath() turns the lobby's relative map path (e.g.
// "! casino extreme v3\! casino extreme v3.map") into the absolute path the
// receiver reconstructs from the file announce. Without this, a relative path
// round-trips through realMapPathToPortableMapPath/portableMapPathToRealMapPath
// to an empty string and the receiver silently drops the file.
// CanSendMapFile() is the single "can the host actually send this?" test, used
// by both the map-contents mask probe and the sender so they can never disagree.
AsciiString ResolveMapTransferPath( AsciiString mapName );
Bool CanSendMapFile( AsciiString path );
AsciiString GetExtensionFromFile( AsciiString fname );
AsciiString GetBaseFileFromFile( AsciiString fname );
AsciiString GetPreviewFromMap( AsciiString path );
AsciiString GetINIFromMap( AsciiString path );
AsciiString GetStrFileFromMap( AsciiString path );
AsciiString GetSoloINIFromMap( AsciiString path );
AsciiString GetAssetUsageFromMap( AsciiString path );
AsciiString GetReadmeFromMap( AsciiString path );

// The meat of file (map) transfers
Bool DoAnyMapTransfers(GameInfo *game);
