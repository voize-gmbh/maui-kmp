package de.voize.mauikmp.plugin

import com.android.build.api.attributes.BuildTypeAttr
import org.gradle.api.attributes.AttributeDisambiguationRule
import org.gradle.api.attributes.MultipleCandidatesDetails

class AndroidBuildTypeAttributeDisambiguationRule : AttributeDisambiguationRule<BuildTypeAttr> {
    override fun execute(t: MultipleCandidatesDetails<BuildTypeAttr>) {
        val availableBuildTypes = t.candidateValues
        val requestedBuildType = t.consumerValue
        if (requestedBuildType == null) {
            val release = availableBuildTypes.find { it.name == "release" }
            val debug = availableBuildTypes.find { it.name == "debug" }
            if (release != null) {
                t.closestMatch(release)
            } else if (debug != null) {
                t.closestMatch(debug)
            } else if (availableBuildTypes.isNotEmpty()) {
                t.closestMatch(availableBuildTypes.first())
            }
        } else {
            if (availableBuildTypes.contains(requestedBuildType)) {
                t.closestMatch(requestedBuildType)
            }
        }
    }
}
