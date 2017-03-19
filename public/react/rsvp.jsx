import React from 'react';
import ReactDOM from 'react-dom';
import isEqual from 'lodash.isequal';
import animateScrollTo from 'animated-scroll-to';


function Button(props) {
    const classes = "button is-large "+props.colour;
    return (
        <button className={classes} value={props.value} onClick={() => props.onClick()}>{props.text}</button>
    )
}

class Textbox extends React.Component {
    constructor(props) {
        super(props);
        this.state = {
            value: props.text
        };
        this.timer = null;

        this.handleChange = this.handleChange.bind(this);
    }

    update(text) {
        this.props.onClick(text)
    }

    handleChange(event) {
        const text = event.target.value;
        this.setState({value: text});
        if (this.timer) window.clearTimeout(this.timer);
        this.timer = window.setTimeout(() => this.update(text), 1200)
    }

    render() {
        const placeholder = this.props.optional ? "" : "Type an answer to continue...";
        return (
            <div>
                <textarea className="textarea" value={this.state.value} onChange={this.handleChange} placeholder={placeholder}/>
            </div>
        );
    }
}

class Selection extends React.Component {
    handleChange(event, key) {
        const newAnswers = Object.assign({}, this.props.answers);
        newAnswers[key] = event.target.checked;
        this.props.onUpdate(newAnswers);
    }
    render() {
        const possibleAnswers = this.props.question.answers;
        const optionsHtml = possibleAnswers.map((answer, index) => {
            const answers = this.props.answers;
            const selected = answers[answer.key];
            return (
                <p key={index} className="control">
                    <label className="checkbox">
                        <input
                            type="checkbox"
                            defaultChecked={selected}
                            onChange={(event) => this.handleChange(event, answer.key)}
                        />
                        {answer.text}
                    </label>
                </p>
            );
        });
        return(
            <div className="selection">
                {optionsHtml}
            </div>
        );
    }
}

class Price extends React.Component {
    amount(currencyInt) {
        const sign = (currencyInt < 0) ? "-" : "";
        const initialString = Math.abs(currencyInt).toString();
        const minimalString = (initialString.length < 3) ? "0" * (3-initialString.length) + initialString : initialString;
        const pounds = minimalString.slice(0, minimalString.length - 2);
        const pence = minimalString.slice(minimalString.length-2, minimalString.length);
        if (pence === "00") {
            return `${sign}£${pounds}`
        } else {
            return `${sign}£${pounds}.${pence}`
        }
    }

    render() {
        const price = this.props.price;
        const subTotal = price.map((p) => p.subTotal).reduce((a, b) => a + b, 0);
        const showBreakdown = price.length > 1 || price.filter((a) => a.english).length > 0;
        const breakdown = price.map((p) => {
            const english = p.english ? " "+p.english : "";
            return this.amount(p.amount)+english+" ("+p.desc+")";
        }).join(" + ");
        const maybeBreakdown = showBreakdown ? " = "+breakdown+"" : "";
        const soleDesc = (price.length == 1 && !price[0].english) ? price[0].desc+" " : "";
        if (subTotal > 0) {
            return(
                <div>
                    <p>{soleDesc}{this.amount(subTotal)}{maybeBreakdown}</p>
                </div>
            );
        } else return null;
    }
}

class Question extends React.Component {
    constructor(props) {
        super(props);
        this.state = {
            scrolled: false
        };
    }
    scrollToBottom() {
        const options = {
            speed: 500,
            minDuration: 250,
            maxDuration: 1000,
            cancelOnUserAction: true
        };
        const body = document.body,
            html = document.documentElement;
        const height = Math.max( body.scrollHeight, body.offsetHeight,
            html.clientHeight, html.scrollHeight, html.offsetHeight );
        animateScrollTo(height, options);
    }
    renderAnswer(questionType) {
        const answers = this.props.question.answers;
        switch (questionType) {
            case "multipleChoice":
                return answers.map((answer, index) => {
                    const colour = (this.props.answer === answer.key) ? "is-success" : "";
                    return (
                        <Button key={index} colour={colour} text={answer.text}
                                onClick={() => this.props.onAnswer(answer.key)}/>
                    );
                });
            case "text":
                return (
                    <Textbox text={this.props.answer} optional={this.props.question.optional} onClick={(newAnswer) => this.props.onAnswer(newAnswer)}/>
                );
            case "selection":
                return (
                    <Selection question={this.props.question} answers={this.props.answer} onUpdate={(newAnswers) => this.props.onAnswer(newAnswers)} />
                );
        }
    }

    componentDidUpdate() {
        if (this.props.scrollTo && !this.state.scrolled) {
            this.scrollToBottom();
            console.log("scrolled in "+this.props.question.key);
            this.setState({scrolled: true});
        }
    }

    render() {
        const question = this.props.question;
        const needHeading = question.helpText ? "" : "heading";
        const htmlQuestion = <h2 className={needHeading}>{question.question}</h2>;
        const htmlAnswer = this.renderAnswer(question.questionType);
        const dangerousHelpText = () => {
            return {__html: question.helpText};
        };
        const price = this.props.price;
        return (
            <div className="question">
                {htmlQuestion}
                {question.helpText &&
                    <p dangerouslySetInnerHTML={dangerousHelpText()}/>
                }
                {htmlAnswer}
                {price &&
                    <Price price={price}/>
                }
            </div>
        )
    }
}

class Rsvp extends React.Component {
    constructor() {
        super();
        this.state = {
            questions: null,
            startKey: null,
            answers: {},
            submittedAnswers: {},
            unsent: true,
            modified: false,
            prices: {}
        }
    }
    findAnswer(question, answerKey) {
        return question.answers.find((answer) => { return answer.key === answerKey });
    }
    componentDidMount() {
        // get questions
        fetch("/rsvp/questions", {
            credentials: 'include'
        }).then((response) => {
            return response.json().then((json) => {
                this.setState({
                    questions: json.questions, startKey: json.startPage, answers: json.answers,
                    submittedAnswers: json.submittedAnswers,
                    unsent: json.unsent, modified: json.modified, prices: json.prices
                });
            });
        });
    }
    update(answers, complete) {
        return fetch("/rsvp/update?complete="+complete, {
            credentials: 'include',
            headers: {
                'Content-Type': 'application/json',
                'Csrf-Token': window.csrf
            },
            method: 'POST',
            body: JSON.stringify(answers)
        })
    }
    onAnswer(question, answer) {
        const answers = {};
        answers[question.key] = answer;
        this.update(answers, false).then((res) => {
            if (res.status == 200) {
                this.setState((previous) => {
                    const newAnswer = {};
                    newAnswer[question.key] = answer;
                    return {answers: Object.assign({}, previous.answers, newAnswer), modified: true};
                });
                res.json().then((json) => {
                    if (json.questions) {
                        this.setState({questions: json.questions});
                    }
                    if (json.prices) {
                        this.setState({prices: json.prices});
                    }
                })
            }
        });
    }
    nextQuestion(question, answerObject) {
        switch(question.questionType) {
            case "multipleChoice":
                return answerObject.nextQuestion;
            case "text":
                return question.nextQuestion;
            case "selection":
                return question.nextQuestion;
        }
    }
    isTerminus(question) {
        return question.nextQuestion === undefined && question.answers.length === 0;
    }
    questionAnswers(key) {
        const question = this.state.questions[key];
        if (question) {
            const answerKey = this.state.answers[question.key];
            const price = this.state.prices.breakdown[question.key];
            if (answerKey || question.optional) {
                const answerObject = this.findAnswer(question, answerKey);
                const nextQuestion = this.nextQuestion(question, answerObject);
                return [{question: question, answer: answerKey, price: price}].concat(this.questionAnswers(nextQuestion));
            } else {
                return [{question: question, price: price}];
            }
        } else return [];
    }
    renderQuestion(question, answer, price, scrollTo) {
        return (
            <Question key={question.key} question={question} answer={answer} price={price} scrollTo={scrollTo} onAnswer={(answer) => this.onAnswer(question, answer)}/>
        );
    }
    finishRsvp() {
        this.update(this.state.answers, true).then((res) => {
            if (res.status == 200) {
                window.location = '/rsvp/complete'
            }
        })
    }
    resetRsvp() {
        return fetch("/rsvp/reset", {
            credentials: 'include',
            headers: {
                'Csrf-Token': window.csrf
            },
            method: 'POST'
        }).then((res) => {
            if (res.status == 204) {
                this.componentDidMount()
            }
        })
    }
    render() {
        if (this.state.questions) {
            const questionList = this.questionAnswers(this.state.startKey);
            const questionElements = questionList.map((question, index) => {
                const bottomQuestion = index === questionList.length-1;
                const lastQuestion = this.isTerminus(question.question);
                const scrollTo = bottomQuestion && !lastQuestion;
                return this.renderQuestion(question.question, question.answer, question.price, scrollTo);
            });
            const bottomQuestion = questionList[questionList.length-1];
            const terminusQuestion = bottomQuestion.question.nextQuestion === undefined;
            const finished = terminusQuestion && (bottomQuestion.answer || bottomQuestion.question.optional);
            const unsent = this.state.unsent;
            const modified = !isEqual(this.state.answers, this.state.submittedAnswers);

            return (
                <div>
                    {finished &&
                        <div className="margin-bottom">
                            {modified && !unsent &&
                            <div className="notification is-danger">
                                NOTE: You've made changes to your RSVP since you last sent it to us. Once you're happy
                                with the changes remember to send them to us!
                            </div>
                            }
                        </div>
                    }
                    {questionElements}
                    {finished &&
                        <div>
                            {!modified && <h2 className="heading">You're finished!</h2>}
                            {modified && unsent && <Button colour="is-danger" onClick={() => this.finishRsvp()} text="Send RSVP"/>}
                            {modified && !unsent &&
                                <div className="question">
                                    <p>You've made changes to your RSVP since you last sent it to us. Remember to send them to us!</p>
                                    <Button colour="is-danger" onClick={() => this.finishRsvp()} text="Update RSVP"/>
                                    <Button colour="is-warning" onClick={() => this.resetRsvp()} text="Forget RSVP changes"/>
                                </div>
                            }
                        </div>
                    }
                </div>
            );
        } else {
            return (
                <p>Loading RSVP information...</p>
            );
        }
    }
}

ReactDOM.render(
    <Rsvp />,
    document.getElementById('container')
);