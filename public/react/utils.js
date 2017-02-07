export function map(o, f, ctx) {
    ctx = ctx || this;
    let result = {};
    Object.keys(o).forEach(function(k) {
        result[k] = f.call(ctx, o[k], k, o);
    });
    return result;
}