package com.paisalens.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pets
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryVisualsTest {
    @Test
    fun choosesPetIconFromCategoryName() {
        assertEquals(Icons.Rounded.Pets, customCategoryIcon("Pet care"))
    }

    @Test
    fun choosesHomeIconFromRentCategoryName() {
        assertEquals(Icons.Rounded.Home, customCategoryIcon("Monthly rent"))
    }

    @Test
    fun doesNotMatchKeywordsInsideUnrelatedWords() {
        assertEquals(Icons.Rounded.Category, customCategoryIcon("Vegas trip"))
    }
}
