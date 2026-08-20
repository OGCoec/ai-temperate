(function(){
  'use strict';
  const SDK_READY_TIMEOUT_MS=15000;
  const SCRIPT_ID='ait-turnstile-sdk';
  const ALLOWED_ACTIONS=new Set(['register','login','password_reset','oauth_phone']);
  const parameters=new URLSearchParams(window.location.search);
  const challenge=parameters.get('challenge')||'';
  const action=parameters.get('action')||'';
  const status=document.getElementById('status');
  const error=document.getElementById('error');
  let siteKey='';
  let channel='';
  let widgetId=null;
  let renderGeneration=0;
  let sdkLoadGeneration=0;
  let readyTimer;
  let sdkFailed=false;
  let tokenDelivered=false;

  function sanitizeCode(value){
    const code=String(value==null?'':value);
    return /^[0-9]{6}$/.test(code)?code:'unknown';
  }

  function resultUrl(kind,fields){
    const entries=[['channel',channel]].concat(fields||[]);
    return 'aiturnstile://'+kind+'?'+entries.map(function(entry){
      return encodeURIComponent(entry[0])+'='+encodeURIComponent(entry[1]);
    }).join('&');
  }

  function dispatchResult(kind,fields){
    if(!channel)return false;
    window.location.href=resultUrl(kind,fields);
    return true;
  }

  function finish(token,generation){
    if(generation!==renderGeneration||tokenDelivered||typeof token!=='string'||!token||token.length>4096)return;
    tokenDelivered=true;
    status.textContent='安全验证已完成，正在确认结果…';
    dispatchResult('verified',[['token',token]]);
  }

  function providerError(rawCode,generation){
    if(generation!==renderGeneration||tokenDelivered)return;
    const code=sanitizeCode(rawCode);
    status.textContent='安全验证出现暂时异常，正在重试…';
    error.textContent='安全验证失败（代码：'+code+'）。';
    dispatchResult('error',[['code',code]]);
  }

  function terminalResult(kind,message,generation){
    if(generation!==renderGeneration||tokenDelivered)return;
    status.textContent=message;
    error.textContent=message;
    dispatchResult(kind);
  }

  function failConfiguration(){
    status.textContent='安全验证配置无效。';
    error.textContent='安全验证配置或流程无效，请重新验证。';
    dispatchResult('error',[['code','config_invalid']]);
  }

  function renderWidget(){
    if(!window.turnstile||typeof window.turnstile.render!=='function'){
      providerError('200500',renderGeneration);
      return;
    }
    const generation=++renderGeneration;
    tokenDelivered=false;
    status.textContent='请完成安全验证。';
    error.textContent='';
    if(widgetId!==null){
      try{
        window.turnstile.remove(widgetId);
        widgetId=null;
      }catch(ignored){
        providerError('200500',generation);
        return;
      }
    }
    try{
      widgetId=window.turnstile.render('#widget',{
        sitekey:siteKey,
        action:action,
        cData:challenge,
        theme:'dark',
        size:'normal',
        language:'auto',
        retry:'auto',
        'retry-interval':8000,
        callback:function(token){finish(token,generation);},
        'error-callback':function(code){providerError(code,generation);},
        'expired-callback':function(){
          terminalResult('expired','安全验证已过期，请重新验证。',generation);
        },
        'timeout-callback':function(){
          terminalResult('timeout','安全验证等待超时，请重新验证。',generation);
        }
      });
    }catch(ignored){
      providerError('200500',generation);
    }
  }

  function sdkFailure(loadGeneration){
    if(loadGeneration!==sdkLoadGeneration||sdkFailed)return;
    sdkFailed=true;
    clearTimeout(readyTimer);
    const script=document.getElementById(SCRIPT_ID);
    if(script)script.remove();
    providerError('200500',renderGeneration);
  }

  window.aitTurnstileSdkReady=function(){
    clearTimeout(readyTimer);
    if(!window.turnstile||typeof window.turnstile.render!=='function'){
      sdkFailure(sdkLoadGeneration);
      return;
    }
    renderWidget();
  };

  function loadSdk(){
    if(window.turnstile&&typeof window.turnstile.render==='function'){
      renderWidget();
      return;
    }
    status.textContent='正在加载安全验证…';
    sdkFailed=false;
    const loadGeneration=++sdkLoadGeneration;
    const stale=document.getElementById(SCRIPT_ID);
    if(stale)stale.remove();
    const script=document.createElement('script');
    script.id=SCRIPT_ID;
    script.async=true;
    script.defer=true;
    script.onerror=function(){sdkFailure(loadGeneration);};
    script.src='https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=aitTurnstileSdkReady';
    readyTimer=setTimeout(function(){sdkFailure(loadGeneration);},SDK_READY_TIMEOUT_MS);
    document.head.appendChild(script);
  }

  function fragmentChannel(hash){
    if(!hash||hash.charAt(0)!=='#')return '';
    const matches=hash.substring(1).split('&').filter(function(part){
      return part.indexOf('channel=')===0;
    });
    if(matches.length!==1)return '';
    try{
      const value=decodeURIComponent(matches[0].substring('channel='.length));
      return /^[A-Za-z0-9_-]{8,80}$/.test(value)?value:'';
    }catch(ignored){
      return '';
    }
  }

  function loadConfig(){
    status.textContent='正在读取安全验证配置…';
    const hash=window.location.hash;
    channel=fragmentChannel(hash);
    try{
      const parts=hash&&hash.charAt(0)==='#'?hash.substring(1).split('&'):[];
      if(parts.length!==2)throw new Error('Turnstile fragment is invalid.');
      const values=new Map();
      parts.forEach(function(part){
        const separator=part.indexOf('=');
        if(separator<=0)throw new Error('Turnstile fragment is invalid.');
        const name=decodeURIComponent(part.substring(0,separator));
        const value=decodeURIComponent(part.substring(separator+1));
        if(values.has(name)||!new Set(['siteKey','channel']).has(name)){
          throw new Error('Turnstile fragment is invalid.');
        }
        values.set(name,value);
      });
      const configuredSiteKey=values.get('siteKey')||'';
      const configuredChannel=values.get('channel')||'';
      if(!/^[A-Za-z0-9_-]{20,200}$/.test(configuredSiteKey)||
        !/^[A-Za-z0-9_-]{8,80}$/.test(configuredChannel)){
        throw new Error('Turnstile fragment is invalid.');
      }
      // Fragment只在受控WebView内交付公开Site Key和一次性通道，读取后立即从历史记录移除。
      window.history.replaceState(null,'',window.location.pathname+window.location.search);
      siteKey=configuredSiteKey;
      channel=configuredChannel;
      loadSdk();
    }catch(ignored){
      failConfiguration();
    }
  }

  if(!/^[A-Za-z0-9_-]{38}$/.test(challenge)||!ALLOWED_ACTIONS.has(action)){
    channel=fragmentChannel(window.location.hash);
    failConfiguration();
    return;
  }
  loadConfig();
})();
