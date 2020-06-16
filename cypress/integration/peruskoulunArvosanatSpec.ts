import kirjautuminenVirkailijanNakymaan from '../testit/kirjautuminenVirkailijanNakymaan'
import lomakkeenLuonti from '../testit/lomakkeenLuonti'
import peruskoulunArvosanatOsionPoistaminen from '../testit/arvosanat/peruskoulunArvosanatOsionPoistaminen'
import peruskoulunArvosanatOsionTayttaminenHakijana from '../testit/arvosanat/peruskoulunArvosanatOsionTayttaminenHakijana'
import peruskoulunArvosanatOsionLisays from '../testit/arvosanat/peruskoulunArvosanatOsionLisays'
import hakijanNakymaanSiirtyminen from '../testit/hakijanNakymaanSiirtyminen'
import peruskoulunArvosanatOsionAidinkielenVaihtaminen from '../testit/arvosanat/peruskoulunArvosanatOsionAidinkielenVaihtaminen'
import henkilotietoModuulinTayttaminen from '../testit/henkilotietoModuulinTayttaminen'
import hakemuksenLahettaminen from '../testit/hakemuksenLahettaminen'

describe('Peruskoulun arvosanat -osio', () => {
  kirjautuminenVirkailijanNakymaan(() => {
    lomakkeenLuonti((lomakkeenTunnisteet) => {
      peruskoulunArvosanatOsionLisays(lomakkeenTunnisteet, () => {
        peruskoulunArvosanatOsionPoistaminen(lomakkeenTunnisteet)
        hakijanNakymaanSiirtyminen(lomakkeenTunnisteet, () => {
          henkilotietoModuulinTayttaminen(() => {
            peruskoulunArvosanatOsionAidinkielenVaihtaminen()
            peruskoulunArvosanatOsionTayttaminenHakijana()
            hakemuksenLahettaminen()
          })
        })
      })
    })
  })
})
