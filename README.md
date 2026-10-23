# Navegador 4.0

[![Android build and tests](https://github.com/emanoelb5-lgtm/Navegador-4.0/actions/workflows/android.yml/badge.svg)](https://github.com/emanoelb5-lgtm/Navegador-4.0/actions/workflows/android.yml)

Navegador Android nativo em **Kotlin**, com **Chromium/Blink pelo Android System WebView** e interface própria. Android 8.0 ou superior. O motor é fornecido e atualizado pelo Android, sem uma cópia embarcada do Chromium ou serviços do Google Chrome. **Este repositório não é um fork completo do Chromium.**

[**Baixar o APK mais recente**](https://github.com/emanoelb5-lgtm/Navegador-4.0/releases/latest) · [Compilações e testes](https://github.com/emanoelb5-lgtm/Navegador-4.0/actions) · [Privacidade](PRIVACY.md)

## Primeira versão — 0.1.0

- Interface nativa com barra inferior, menu por painel, tema claro/escuro do sistema e ícones vetoriais.
- Endereço e pesquisa, seleção entre DuckDuckGo/Google/Bing e abertura de links do Android.
- Até 30 abas, criação sob demanda, navegação voltar/avançar e restauração dos endereços das abas normais.
- Favoritos, histórico, pesquisa de texto na página e modo desktop.
- Downloads com confirmação pelo gerenciador do Android, consulta, abertura e remoção.
- Uploads pelo seletor do Android, áudio/vídeo em tela cheia e permissões de câmera/microfone/localização por solicitação da origem HTTPS ativa.
- Abas privadas com perfis isolados nos motores compatíveis. Em motores sem suporte, o aplicativo solicita atualização em vez de simular privacidade.
- Proteção contra certificados inválidos, Safe Browsing do motor, bloqueio de cookies de terceiros, bloqueio de acesso arbitrário a arquivos e ausência de pontes JavaScript nativas.
- Tratamento da queda do processo de renderização com opção de reabrir a página.

## Limites desta entrega

É a primeira versão de testes, ainda sem validação em todos os aparelhos e sites. O motor do sistema pode ter compatibilidade diferente do Chrome completo. Não há suporte a extensões, sincronização, IA, comparação de páginas ou espaços de pesquisa nesta entrega. Autenticação de downloads por cookies, links `blob:`, captura direta de câmera no seletor de arquivos, DRM específico de serviços e recursos de Chrome indisponíveis no WebView não são garantidos. Downloads não recebem cookies de sessão para evitar o encaminhamento de credenciais a redirecionamentos. Android 8/9 guarda downloads na pasta externa privada do aplicativo; Android 10+ usa a pasta Downloads.

A restauração após encerramento do processo recupera os endereços das abas normais, não o conteúdo de formulários. A economia de memória mantém até quatro páginas em memória quando possível e protege páginas com formulários editados ou mídia detectada em reprodução; em pressão de memória abas elegíveis são reconstruídas pelo estado do WebView. Abas privadas nunca são persistidas pelo aplicativo.

## Compilar e validar

JDK 17, Android SDK 36, Build Tools 35.0.0 e Gradle 8.13.

```sh
./gradlew testDebugUnitTest lintDebug assembleRelease
./gradlew connectedDebugAndroidTest
```

O GitHub Actions compila o APK, executa os testes de política de URL, lint e testes instrumentados em Android 11. A compilação entrega um APK sem assinatura e a ferramenta de assinatura em um artefato de curta duração.

## Assinatura e publicação

A assinatura permanente é aplicada **fora do GitHub**, usando a chave privada e `scripts/sign_release.py`. Apenas o certificado público está em `signing/`. A chave e sua senha nunca devem ser enviadas ao repositório, aos logs ou aos artefatos do Actions.

O APK assinado e seus metadados são preparados em uma branch `release/*`. O workflow de publicação verifica o checksum, o certificado público e a aprovação dos testes do commit de origem antes de criar a release. As próximas atualizações devem manter o mesmo applicationId e certificado e aumentar versionCode/versionName.

## Base técnica

- Kotlin e Views nativas com Material Components.
- AndroidX WebKit para recursos versionados e detecção de capacidades do motor.
- [Blink](https://www.chromium.org/blink/) · [Android WebView](https://developer.android.com/reference/android/webkit/WebView).
- Código original sob licença [MIT](LICENSE); dependências preservam suas próprias licenças. O motor do Android não é redistribuído neste APK.
