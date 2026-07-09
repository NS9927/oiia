package net.posdaca.oiia.core

import icu.windea.pls.ep.analysis.ParadoxInferredGameTypeProvider
import icu.windea.pls.model.ParadoxGameType
import icu.windea.pls.model.ParadoxGameTypeInfo
import java.nio.file.Files
import java.nio.file.Path

class HoI4GameTypeProvider : ParadoxInferredGameTypeProvider {
    override fun getInferredGameTypeInfo(rootPath: Path): ParadoxGameTypeInfo? {
        if (Files.isRegularFile(rootPath.resolve("hoi4.exe"))) {
            return ParadoxGameTypeInfo(ParadoxGameType.Hoi4) { "Hearts of Iron IV" }
        }
        return null
    }
}
