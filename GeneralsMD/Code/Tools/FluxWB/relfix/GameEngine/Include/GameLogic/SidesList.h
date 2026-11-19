// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @build Flux Build 07/11/2026 PLAN-032: EA's World Builder headers include
// "../../GameEngine/Include/GameLogic/SidesList.h" relative to their folder. In this tree that header now lives
// elsewhere (Core/ or GeneralsMD/), so the relative path would fall through to the base game's copy.
// FluxWB puts relfix/a/b first on the include path, which makes that relative path land here.
#pragma once
#include <GameLogic/SidesList.h>
