package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineLexicalContext
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineModelContext

internal fun InlineModelContext.hasUsefulSignal(): Boolean =
    lexicalContext == InlineLexicalContext.COMMENT ||
        lexicalContext == InlineLexicalContext.STRING ||
        enclosingNames.isNotEmpty() ||
        enclosingKinds.isNotEmpty() ||
        !currentDefinitionName.isNullOrBlank() ||
        currentParameterNames.isNotEmpty() ||
        isFreshBlockBodyContext ||
        isDecoratorLikeContext ||
        isClassBaseListLikeContext ||
        isAfterMemberAccess ||
        !receiverExpression.isNullOrBlank() ||
        receiverMemberNames.isNotEmpty() ||
        isInParameterListLikeContext ||
        isDefinitionHeaderLikeContext ||
        !classBaseReferencePrefix.isNullOrBlank() ||
        matchingTypeNames.isNotEmpty() ||
        !resolvedReferenceName.isNullOrBlank() ||
        !resolvedSnippet.isNullOrBlank() ||
        resolvedDefinitions.isNotEmpty()
