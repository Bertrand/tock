package fr.vsct.tock.nlp.front.shared.build

import fr.vsct.tock.nlp.front.shared.config.ApplicationDefinition
import kotlin.Boolean
import kotlin.String
import kotlin.Suppress
import kotlin.collections.Collection
import kotlin.reflect.KProperty1
import org.litote.kmongo.Id
import org.litote.kmongo.property.KCollectionPropertyPath
import org.litote.kmongo.property.KPropertyPath

class ModelBuildTrigger_<T>(previous: KPropertyPath<T, *>?, property: KProperty1<*, ModelBuildTrigger?>) : KPropertyPath<T, ModelBuildTrigger?>(previous,property) {
    val applicationId: KProperty1<T, Id<ApplicationDefinition>?>
        get() = org.litote.kmongo.property.KPropertyPath(this,ModelBuildTrigger::applicationId)

    val all: KProperty1<T, Boolean?>
        get() = org.litote.kmongo.property.KPropertyPath(this,ModelBuildTrigger::all)

    val onlyIfModelNotExists: KProperty1<T, Boolean?>
        get() = org.litote.kmongo.property.KPropertyPath(this,ModelBuildTrigger::onlyIfModelNotExists)
    companion object {
        val ApplicationId: KProperty1<ModelBuildTrigger, Id<ApplicationDefinition>?>
            get() = ModelBuildTrigger::applicationId
        val All: KProperty1<ModelBuildTrigger, Boolean?>
            get() = ModelBuildTrigger::all
        val OnlyIfModelNotExists: KProperty1<ModelBuildTrigger, Boolean?>
            get() = ModelBuildTrigger::onlyIfModelNotExists}
}

class ModelBuildTrigger_Col<T>(
        previous: KPropertyPath<T, *>?,
        property: KProperty1<*, Collection<ModelBuildTrigger>?>,
        additionalPath: String? = null
) : KCollectionPropertyPath<T, ModelBuildTrigger?>(previous,property,additionalPath) {
    override val arrayProjection: ModelBuildTrigger_Col<T>
        @Suppress("UNCHECKED_CAST")
        get() = ModelBuildTrigger_Col(null, this as KProperty1<*, Collection<ModelBuildTrigger>?>, "$")

    val applicationId: KProperty1<T, Id<ApplicationDefinition>?>
        get() = org.litote.kmongo.property.KPropertyPath(this,ModelBuildTrigger::applicationId)

    val all: KProperty1<T, Boolean?>
        get() = org.litote.kmongo.property.KPropertyPath(this,ModelBuildTrigger::all)

    val onlyIfModelNotExists: KProperty1<T, Boolean?>
        get() = org.litote.kmongo.property.KPropertyPath(this,ModelBuildTrigger::onlyIfModelNotExists)
}
