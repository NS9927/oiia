package net.posdaca.oiia.core

import icu.windea.pls.ep.analysis.ParadoxInferredGameTypeProvider
import icu.windea.pls.model.ParadoxGameType
import java.nio.file.Files
import java.nio.file.Path

class HoI4GameTypeProvider : ParadoxInferredGameTypeProvider {
    override fun get(rootPath: Path): ParadoxGameType? {
        if (Files.isRegularFile(rootPath.resolve("hoi4.exe"))) {
            return ParadoxGameType.Hoi4
        }
        return null
    }
}
