# YKI / Tunnistautumisflow

- Kaksi eri tunnistautumisflow'ta
    * sähköpostilinkki
    * Suomi.fi-tunnistautuminen
- Näistä fokus Suomi.fi-tunnistautumisessa

## Suomi.fi-tunnistautuminen

1. Käyttäjä klikkaa painiketta `Tunnistaudu Suomi.fi:n kautta`
2. Selain lähettää palvelimelle pyynnön `GET /yki/auth`
    1. query-parametreina redirectin tarvitsemia tietoja: UIn kielivalinta, koetilaisuuden ID
    2. myös session tietoihin tallennetaan osoite johon onnistuneen tunnistautumisen jälkeen käyttäjä olisi syytä uudelleenohjata
3. autentikaatio-middleware uudelleenohjaa selaimen Opintopolun tunnistautumispalveluun `CAS-oppija`
    1. `303 location: https://opintopolku.fi/cas-oppija/login?locale=fi&service=https://yki.opintopolku.fi/yki/auth/callbackFI?examSessionId=999`
4. Tunnistautuminen Opintopolun laajuisesti tapahtuu
    1. Jos tunnistautuminen epäonnistuu / keskeytetään, käyttäjä voidaan luultavasti ohjata takaisin palveluun - tämä ei kuitenkaan toteutettu YKIssä, vaan paluu palveluun vie Opintopolun etusivulle
    2. Jatkossa oletetaan että tunnistautuminen suoritettiin onnistuneesti.
5. CAS-palvelin asettaa pitkäikäisen Ticket Granting Ticketin käyttäjän SSO-session ajaksi, tiketti tallennettaan keksiin
6. CAS-palvelin ohjaa käyttäjän takaisin `service`-query-parametrinä annettuun osoitteeseen (kohta 3.i)
    1. kutsussa mukana lisäksi query-parametrinä lyhytikäinen ja kertakäyttöinen Service Ticket: https://yki.opintopolku.fi/yki/auth/callbackFI?examSessionId=999&ticket=ST-1-************-***-**********-ip-123-234-56-78
7. YKI-palvelin käsittelee kutsun ja yrittää validoida saamansa Service Ticketin
    1. `GET https://opintopolku.fi/cas-oppija/validateService?ticket=ST-1-************-***-**********-ip-123-234-56-78&service=https://yki.opintopolku.fi/yki/auth/callbackFI?examSessionId=999`
    2. HUOM: Yllä annettu redirect-URL on sama kuin kohdassa 3, mutta sitä ei ilmeisesti käytetä mihinkään?
    3. Vastauksesta tarkistetaan status (virhe jos ei 200) ja prosessoidaan bodyssä tuleva XML-sanoma
8. XML-sanomasta tarkistetaan validaation lopputulos
    1. Mikäli tiketti oli validi, XML-sanomasta parsitaan henkilön tiedot
    2. YKIn tapauksessa henkilön tiedoista voidaan olettaa löytyvän mm. nimi, hetu, osoite, ...
    3. Henkilön tiedot tallennetaan käyttäjän sessioon
9. Käyttäjä ohjataan joko success-redirectiin (asetettu session tietoihin kohdassa 2.ii) tai palautetaan `401 Unauthorized`
10. Onnistuneen autentikaation jälkeen selain ohjataan ilmoittautumissivulle, joka esitäyttää käyttäjän tiedot lomakkeelle
    1. Käyttäjän tietojen haku sessioon tallennetuista tiedoista: `GET /yki/auth/user`
