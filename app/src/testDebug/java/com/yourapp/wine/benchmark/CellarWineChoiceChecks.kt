package com.yourapp.wine.benchmark

import com.yourapp.wine.fill.FieldId

object CellarWineChoiceChecks {
    fun searchAndExpectedFields() {
        val wine=CellarWineChoice(42,"Vignerons de l’Enclave","La Réserve de l’Enclave","France","Rhone","Côtes du Rhône Villages","2022")
        check(wine.matches("reserve 2022"))
        check(wine.matches("RHONE vignerons"))
        check(!wine.matches("reserve 2018"))
        check(wine.matches(""))
        val expected=wine.expected()
        check(expected.keys==FieldId.entries.toSet())
        check(expected[FieldId.PRODUCER]==wine.maker)
        check(expected[FieldId.WINE_NAME]==wine.name)
        check(expected[FieldId.SUBREGION]==wine.subregion)
        check(expected[FieldId.VINTAGE]=="2022")
        val otherVintage=wine.copy(bottleId=43,vintage="2019")
        check(otherVintage.title!=wine.title && otherVintage.bottleId!=wine.bottleId)
        check(otherVintage.expected()[FieldId.VINTAGE]=="2019")
        for(vintage in listOf("NV","(NV)","","null","unknown")) {
            check(wine.copy(vintage=vintage).expected()[FieldId.VINTAGE]=="")
        }
        val us=wine.copy(country="California",region="",subregion="",vintage="NV")
        check(us.expected()[FieldId.COUNTRY]=="California")
        check(us.expected()[FieldId.REGION]=="" && us.expected()[FieldId.SUBREGION]=="")
        check(expected[FieldId.VINTAGE]=="2022") // Other selections cannot mutate frozen truth.
    }
}
