# Privacidade — Navegador 4.0

A versão 0.1.0 não inclui conta, anúncios, analytics, SDK de publicidade, IA ou servidor próprio. Buscas e páginas são enviadas ao provedor/site escolhido. O Android System WebView pode realizar verificações de segurança e tráfego próprios do motor; consulte as políticas do Android e do provedor do aparelho.

Favoritos, endereços das abas e histórico ficam no armazenamento privado do aplicativo. O backup automático do Android está desativado. Câmera, microfone e localização exigem autorização do site e permissão do Android, são limitados à origem HTTPS da página ativa e não são concedidos permanentemente pelo aplicativo. Cookies de terceiros são bloqueados.

Arquivos só podem ser selecionados pelo seletor do Android. Downloads exigem confirmação. Sites não recebem interfaces Java/Kotlin. Links externos exigem interação do usuário e confirmação antes de abrir outro aplicativo. Certificados TLS inválidos são recusados.

Abas privadas só são habilitadas quando o WebView suporta perfis isolados e exclusão completa de dados. O perfil privado é separado do normal. O aplicativo não registra o histórico ou os endereços das abas privadas e limpa cookies, cache e dados desse perfil ao encerrar a sessão. Se o processo for encerrado inesperadamente, os perfis privados remanescentes são excluídos no próximo início do motor. Isso não fornece anonimato diante de sites, administradores de rede ou provedores de internet. Downloads e favoritos salvos explicitamente continuam existindo após o encerramento da sessão.

Use Configurações → Limpar dados de navegação para remover histórico, cookies e dados dos sites. Favoritos e arquivos baixados são mantidos. Em motores antigos sem exclusão completa de dados, a limpeza usa as APIs legadas; atualize o WebView para suporte completo. A exclusão de downloads é feita na tela Downloads.
