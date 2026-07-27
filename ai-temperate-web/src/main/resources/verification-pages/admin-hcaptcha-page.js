(function(){
  'use strict';
  const MAX_AUTO_RETRIES=1;
  const SDK_READY_TIMEOUT_MS=15000;
  const SCRIPT_ID='ait-admin-hcaptcha-sdk';
  const parameters=new URLSearchParams(window.location.hash.slice(1));
  const siteKey=parameters.get('siteKey')||'';
  const challenge=parameters.get('challenge')||'';
  const status=document.getElementById('status');
  const error=document.getElementById('error');
  const retry=document.getElementById('retry');
  let widgetId=null;
  let renderGeneration=0;
  let sdkLoadGeneration=0;
  let autoRetryCount=0;
  let retryTimer;
  let readyTimer;
  let errorHandled=false;
  let tokenDelivered=false;

  function sanitizeCode(value){
    const code=String(value==null?'':value);
    return ['network-error','challenge-error','internal-error','invalid-data','rate-limited','script-error'].indexOf(code)>=0?code:'unknown';
  }
  function isRetryable(code){
    return code==='network-error'||code==='challenge-error'||code==='internal-error'||code==='script-error';
  }
  function hideFailure(){
    error.textContent='';
    retry.hidden=true;
  }
  function showFailure(code){
    errorHandled=true;
    clearTimeout(retryTimer);
    status.textContent='验证未完成，请手动重试。';
    error.textContent='管理员安全验证失败（代码：'+code+'）。';
    retry.hidden=false;
    retry.focus();
  }
  function cancel(){
    window.location.href='aithcaptcha://cancelled?cancelled=1&challenge='+encodeURIComponent(challenge);
  }
  function finish(token,generation){
    if(generation!==renderGeneration||tokenDelivered||!token)return;
    tokenDelivered=true;
    clearTimeout(retryTimer);
    window.location.href='aithcaptcha://verified?token='+encodeURIComponent(token)+'&challenge='+encodeURIComponent(challenge);
  }
  function resetWidget(generation){
    if(generation!==renderGeneration||!window.hcaptcha||widgetId===null)return;
    errorHandled=false;
    tokenDelivered=false;
    hideFailure();
    status.textContent='请完成下方安全验证。';
    try{
      window.hcaptcha.reset(widgetId);
    }catch(ignored){
      handleError('internal-error',generation);
    }
  }
  function retryCurrent(generation){
    if(generation!==renderGeneration)return;
    if(window.hcaptcha&&widgetId!==null){resetWidget(generation);return;}
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
        errorHandled=false;
        retryCurrent(generation);
      },1000);
      return;
    }
    showFailure(code);
  }
  function renderWidget(){
    if(!window.hcaptcha||typeof window.hcaptcha.render!=='function'){
      handleError('script-error',renderGeneration);
      return;
    }
    const generation=++renderGeneration;
    errorHandled=false;
    tokenDelivered=false;
    hideFailure();
    status.textContent='请完成下方安全验证。';
    if(widgetId!==null){
      if(typeof window.hcaptcha.remove!=='function'){
        handleError('internal-error',generation);
        return;
      }
      try{
        window.hcaptcha.remove(widgetId);
        widgetId=null;
      }catch(ignored){
        handleError('internal-error',generation);
        return;
      }
    }
    try{
      widgetId=window.hcaptcha.render('widget',{
        sitekey:siteKey,
        callback:function(token){finish(token,generation);},
        'expired-callback':function(){handleError('challenge-error',generation);},
        'error-callback':function(code){handleError(code,generation);},
        'close-callback':function(){if(generation===renderGeneration)cancel();}
      });
    }catch(ignored){
      handleError('internal-error',generation);
    }
  }
  function sdkFailure(loadGeneration){
    if(loadGeneration!==sdkLoadGeneration)return;
    clearTimeout(readyTimer);
    const script=document.getElementById(SCRIPT_ID);
    if(script)script.remove();
    handleError('script-error',renderGeneration);
  }
  window.aitHcaptchaSdkReady=function(){
    clearTimeout(readyTimer);
    if(!window.hcaptcha||typeof window.hcaptcha.render!=='function'){
      sdkFailure(sdkLoadGeneration);
      return;
    }
    renderWidget();
  };
  function loadSdk(){
    if(window.hcaptcha&&typeof window.hcaptcha.render==='function'){
      renderWidget();
      return;
    }
    const loadGeneration=++sdkLoadGeneration;
    hideFailure();
    status.textContent='正在加载安全验证…';
    const stale=document.getElementById(SCRIPT_ID);
    if(stale)stale.remove();
    const script=document.createElement('script');
    script.id=SCRIPT_ID;
    script.async=true;
    script.defer=true;
    script.onerror=function(){sdkFailure(loadGeneration);};
    script.src='https://js.hcaptcha.com/1/api.js?render=explicit&recaptchacompat=off&onload=aitHcaptchaSdkReady';
    readyTimer=setTimeout(function(){sdkFailure(loadGeneration);},SDK_READY_TIMEOUT_MS);
    document.head.appendChild(script);
  }
  retry.addEventListener('click',function(){
    clearTimeout(retryTimer);
    autoRetryCount=0;
    errorHandled=false;
    hideFailure();
    retryCurrent(renderGeneration);
  });
  document.getElementById('cancel').addEventListener('click',cancel);
  if(!/^[A-Za-z0-9_-]{1,200}$/.test(siteKey)||!/^[A-Za-z0-9_-]{43}$/.test(challenge)){
    status.textContent='验证流程无效，请返回后重试。';
    retry.hidden=true;
    return;
  }
  loadSdk();
})();
