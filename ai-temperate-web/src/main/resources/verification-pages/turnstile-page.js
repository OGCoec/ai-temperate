(function(){
  'use strict';
  const MAX_AUTO_RETRIES=1;
  const SDK_READY_TIMEOUT_MS=15000;
  const SCRIPT_ID='ait-turnstile-sdk';
  const ALLOWED_ACTIONS=new Set(['register','login','password_reset']);
  const parameters=new URLSearchParams(window.location.search);
  const challenge=parameters.get('challenge')||'';
  const action=parameters.get('action')||'';
  const status=document.getElementById('status');
  const error=document.getElementById('error');
  const retry=document.getElementById('retry');
  let siteKey='';
  let widgetId=null;
  let renderGeneration=0;
  let configGeneration=0;
  let sdkLoadGeneration=0;
  let autoRetryCount=0;
  let retryTimer;
  let readyTimer;
  let errorHandled=false;
  let tokenDelivered=false;

  function sanitizeCode(value){
    const code=String(value==null?'':value);
    return /^[0-9]{6}$/.test(code)?code:'unknown';
  }
  function isRetryable(code){
    return code==='110600'||code==='110620'||code==='200500'||/^(300|600)[0-9]{3}$/.test(code);
  }
  function hideFailure(){
    error.textContent='';
    retry.hidden=true;
  }
  function showFailure(code,message){
    errorHandled=true;
    clearTimeout(retryTimer);
    status.textContent='验证未完成，请手动重试。';
    error.textContent=message||'安全验证失败（代码：'+code+'）。';
    retry.hidden=false;
    retry.focus();
  }
  function finish(token,generation){
    if(generation!==renderGeneration||tokenDelivered||!token)return;
    tokenDelivered=true;
    clearTimeout(retryTimer);
    window.location.href='aiturnstile://verified?token='+encodeURIComponent(token);
  }
  function renderWidget(){
    if(!window.turnstile||typeof window.turnstile.render!=='function'){
      handleError('200500',renderGeneration);
      return;
    }
    const generation=++renderGeneration;
    errorHandled=false;
    tokenDelivered=false;
    hideFailure();
    status.textContent='请完成下方安全验证。';
    if(widgetId!==null){
      try{
        window.turnstile.remove(widgetId);
        widgetId=null;
      }catch(ignored){
        handleError('200500',generation);
        return;
      }
    }
    try{
      widgetId=window.turnstile.render('#widget',{
        sitekey:siteKey,
        action:action,
        cData:challenge,
        theme:'dark',
        retry:'never',
        callback:function(token){finish(token,generation);},
        'error-callback':function(code){handleError(code,generation);},
        'expired-callback':function(){
          if(generation===renderGeneration)showFailure('unknown','安全验证已过期，请重新验证。');
        },
        'timeout-callback':function(){
          if(generation===renderGeneration)showFailure('unknown','安全验证等待超时，请重新验证。');
        }
      });
    }catch(ignored){
      handleError('200500',generation);
    }
  }
  function retryCurrent(generation){
    if(generation!==renderGeneration)return;
    if(!siteKey){loadConfig();return;}
    if(window.turnstile&&typeof window.turnstile.render==='function'){renderWidget();return;}
    loadSdk();
  }
  function handleError(rawCode,generation){
    if(generation!==renderGeneration||errorHandled)return;
    errorHandled=true;
    tokenDelivered=false;
    const code=sanitizeCode(rawCode);
    if(isRetryable(code)&&autoRetryCount<MAX_AUTO_RETRIES){
      autoRetryCount+=1;
      status.textContent='验证出现暂时异常（代码：'+code+'），正在自动重试…';
      retry.hidden=true;
      retryTimer=setTimeout(function(){
        if(generation!==renderGeneration)return;
        errorHandled=false;
        retryCurrent(generation);
      },1000);
      return;
    }
    showFailure(code);
  }
  function sdkFailure(loadGeneration){
    if(loadGeneration!==sdkLoadGeneration)return;
    clearTimeout(readyTimer);
    const script=document.getElementById(SCRIPT_ID);
    if(script)script.remove();
    handleError('200500',renderGeneration);
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
    hideFailure();
    status.textContent='正在加载安全验证…';
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
  async function loadConfig(){
    const generation=++configGeneration;
    hideFailure();
    status.textContent='正在读取安全验证配置…';
    try{
      const response=await fetch('/api/auth/turnstile/config',{
        method:'GET',
        credentials:'same-origin',
        cache:'no-store',
        headers:{Accept:'application/json'}
      });
      if(!response.ok)throw new Error('Turnstile config request failed.');
      const payload=await response.json();
      if(generation!==configGeneration)return;
      const configuredSiteKey=String(payload&&payload.siteKey||'');
      if(!/^[A-Za-z0-9_-]{20,200}$/.test(configuredSiteKey)){
        throw new Error('Turnstile site key is invalid.');
      }
      siteKey=configuredSiteKey;
      loadSdk();
    }catch(ignored){
      if(generation===configGeneration)handleError('200500',renderGeneration);
    }
  }
  retry.addEventListener('click',function(){
    clearTimeout(retryTimer);
    autoRetryCount=0;
    errorHandled=false;
    hideFailure();
    retryCurrent(renderGeneration);
  });
  document.getElementById('cancel').addEventListener('click',function(){
    window.location.href='aiturnstile://cancelled';
  });
  if(!/^[A-Za-z0-9_-]{38}$/.test(challenge)||!ALLOWED_ACTIONS.has(action)){
    showFailure('unknown','验证流程无效，请返回后重试。');
    return;
  }
  loadConfig();
})();
